// vim: ts=4 sts=4 sw=4 et
import groovy.json.JsonSlurper
import org.yaml.snakeyaml.Yaml
// When we're at groovy 3.0
//import groovy.yaml.YamlSlurper
import java.io.FileNotFoundException
import java.io.IOException
import java.security.SignatureException

// Used for merging .jenkins.yaml in to default env
def addNested(lhs, rhs) {
    rhs.each { k, v -> lhs[k] = lhs[k] in Map ? addNested(lhs[k], v) : v }
    return lhs
}

// https://stackoverflow.com/questions/7087185/retry-after-exception-in-groovy
def retry_get_file(int times = 5, Closure errorHandler = {e-> out.println(e.message)}, Closure body) {
    int retries = 0
    def exceptions = []
    while(retries++ < times) {
        try {
            return body.call()
        } catch(SocketException e) {
            exceptions << e
            errorHandler.call(e)
            sleep(retries * 1000)  // Incremental backoff, +1s per try
        }
    }
    throw new RuntimeException("Failed getting file after $times retries")
}

def try_get_file(url) {
    retry_get_file(10) {
        if (!(url instanceof URL))
            url = url.toURL()
        def conn = url.openConnection()
        // So you don't run into the request api request limit as quickly...
        if (binding.hasVariable("GITHUB_TOKEN") && "${GITHUB_TOKEN}" != "" && url.getHost() == 'api.github.com')
            conn.addRequestProperty("Authorization", "token ${GITHUB_TOKEN}")
        return conn.getInputStream().getText()
    }
}

def _repo_file(full_name, branch, fn) {
    return "https://raw.githubusercontent.com/${full_name}/${branch}/${fn}"
}

def _is_disabled(env) {
    return env.disabled.toBoolean();
}

def _build_in_docker(env) {
    if (_get_bool(env.build_in_docker.disable, false)) {
        out.println("${env.full_name} not building in docker. build_in_docker.disable: ${env.build_in_docker.disable}")
        return false
    } else if (env.builders == ["docker"]) {
        out.println("${env.full_name} not building in docker. \"docker\" is the only item in builders: ${env.builders}")
        return false
    } else if (env.build_in_docker.image == null && env.build_in_docker.dockerfile == null) {
        out.println("${env.full_name} not building in docker. build_in_docker.mage: ${env.build_in_docker.image} and build_in_docker.dockerfile: ${env.build_in_docker.dockerfile}")
        return false
    }
    return true
}

def _slack_enabled(env) {
    if (env.slack.room != null || _get_bool(env.slack.disabled, false)) {
        return true
    }
    return false
}

def _managed_script_enabled(env, script_name) {
    if (env.managed_scripts != null && script_name in env.managed_scripts) {
        return true
    }
    return false
}

def _get_bool(value, default_value) {
    if (value != null) {
        return value.toBoolean()
    }
    return default_value.toBoolean()
}

def _get_int(value, default_value) {
    if (value != null) {
        return value.toInteger()
    }
    return default_value.toInteger()
}


repo_must_be_signed = [:].withDefault{ false }


def load_env(repo) {
    // Default environment
    def env = [
        'name'                   : repo.name,
        'full_name'              : repo.full_name.toLowerCase(),
        'repo_full_name'         : repo.full_name, // Jenkins is not case insensitive with push notifications
        'repo_must_be_sighed'    : repo_must_be_signed[repo.full_name],
        'disabled'               : false,
        'git'                    : [:],
        'python_source_directory': 'src',
        'slack'                  : ['room': 'devops-builds', 'disabled': false],
        'triggers'               : [:],
        'builders'               : [],
        'docker_name'            : repo.full_name.toLowerCase(),
        'build_in_docker'        : [
            'disabled': false,
            'dockerfile': null,
            'image': null,
            'start_command': "/run.sh"
        ]
    ]

    // Load enviroment variables from repo yaml file
    try {
        // Check if github says the branch is signed before reading .jenkins.yaml from it.
        if (repo_must_be_signed[env.repo_full_name] && !is_github_branch_signed(env.repo_full_name, "master")) {
            throw new SignatureException("Repo not SIGNED!")
        }
        def yaml_text = try_get_file(_repo_file(env.repo_full_name, "master", ".jenkins.yaml"))
        // Mangle broken yaml into something a propper yaml parser stands
        def fixed_yaml_text = yaml_text.replaceAll('cron: (@\\w+)', 'cron: "$1"')
        if (yaml_text != fixed_yaml_text)
            out.println("FIXME: This repo contains non compliant yaml")
        def repo_env = new Yaml().load(fixed_yaml_text)
        env = addNested(env, repo_env)
    } catch (FileNotFoundException ex) {
        out.println("No .jenkins.yaml for ${env.full_name}... will use defaults")
    }

    // detecting builders
    if (env.builder != null && env.builders.size() == 0) {
        out.println("DEPRECATION WARNING. Use builders.")
        out.println("Builder ${env.builder} found for ${env.full_name}, added to builders")
        env.builders += env.builder
    }

    // If builder or builders is empty try to guess
    if (env.builders == null || env.builders.size() == 0) {
        out.println("No builders found for ${env.full_name}... trying to guess")
        env.builders = []

        try {
            if (!env.name.equals("bootstrap-docker-builds") && try_get_file(_repo_file(env.repo_full_name, "master", "Dockerfile"))) {
                out.println("Found Dockerfile for ${env.full_name}. Adding \"docker\" to builders.")
                env.builders += "docker"
            }
        } catch (FileNotFoundException ex) { }

        try {
            if (try_get_file(_repo_file(env.repo_full_name, "master", "setup.py")).contains("python")) {
                out.println("Found setup.py for ${env.full_name}. Adding \"python\" to builders.")
                env.builders += "python"
            }
        } catch (FileNotFoundException ex) { }

        if (env.script != null) {
            out.println("script set for ${env.full_name}. Adding \"script\" to builders.")
            env.builders += "script"
        }

        try {
            if (try_get_file(_repo_file(env.repo_full_name, "master", "CMakeLists.txt"))) {
                out.println("Found CMakeLists.txt for ${env.full_name}. Adding \"cmake\" to builders.")
                env.builders += "cmake"
            }
        } catch (FileNotFoundException ex) { }
    }

    // detecting wrappers
    try {
        if (try_get_file(_repo_file(env.repo_full_name, "master", "Dockerfile.jenkins")).contains("FROM")) {
            out.println("Found Dockerfile.jenkins for ${env.full_name}. Will be used for build.")
            env.build_in_docker.dockerfile = "Dockerfile.jenkins"
        }
    } catch (FileNotFoundException ex) { }

    if (env.build_in_docker.dockerfile == null && env.build_in_docker.image == null) {
        out.println("No explicit build in docker settings found for ${env.full_name}. Will use docker.sunet.se/sunet/docker-jenkins-job.")
        env.build_in_docker.image = "docker.sunet.se/sunet/docker-jenkins-job"
    } else {
        if (env.build_in_docker.dockerfile != null) {
            out.println("Using dockerfile ${env.build_in_docker.dockerfile} to build ${env.full_name}.")
        } else {
            out.println("Using image ${env.build_in_docker.image} to build ${env.full_name}.")
        }
    }

    return env
}

def is_github_branch_signed(repo_full_name, branch) {
    def js = new JsonSlurper()
    def ref = try_get_file("https://api.github.com/repos/${repo_full_name}/git/ref/heads/${branch}")
    ref = js.parseText(ref)
    def commit = try_get_file(ref["object"]["url"])
    commit = js.parseText(commit)
    return commit["verification"]["verified"]
}

// No def, global
docker_cloud_name = null
for (def cloud : jenkins.model.Jenkins.getInstance().clouds) {
    // job-dsl can't access plugin classes, so just compare it as a string
    // instead of using instanceof
    if (cloud.getClass().toString() == 'class com.nirima.jenkins.plugins.docker.DockerCloud') {
        docker_cloud_name = cloud.name
        break;
    }
}
if (!docker_cloud_name)
    throw new Exception("Can't find a docker cloud to run containers on!")

def add_job(env, is_dev_mode) {
    if (env.builders.size() > 0 && !_is_disabled(env)) {
        out.println("generating job for ${env.full_name} using builders: ${env.builders}")


        job(env.name) {
            // Clear any description
            description("")
            properties {
                githubProjectUrl("https://github.com/${env.repo_full_name}")
                // Build in docker
                if (_build_in_docker(env)) {
                    dockerJobTemplateProperty {
                        cloudname(docker_cloud_name)
                        template {
                            // Name the container after what we build in it
                            name("docker-${env.full_name}")
                            pullStrategy(is_dev_mode ? "PULL_NEVER" : (env.build_in_docker.force_pull ? "PULL_ALWAYS" : "PULL_LATEST") )
                            // Connect as whatever the template tries to run as
                            connector {
                                attach {
                                    user("")
                                }
                            }
                            labelString('')
                            // TODO: Implement verbose?
                            //verbose(_get_bool(env.build_in_docker.verbose, false))
                            // Let global limit handle this
                            instanceCapStr('0')
                            dockerTemplateBase {
                                dockerTemplateBase {
                                    // Enable docker in docker
                                    volumesString(
                                        '/usr/bin/docker:/usr/bin/docker:ro\n' +
                                        '/var/run/docker.sock:/var/run/docker.sock'
                                    )
                                    dockerCommand(env.build_in_docker.start_command)
                                    tty(true)
                                    if (env.build_in_docker.image != null) {
                                        out.println("${env.full_name} building in docker image ${env.build_in_docker.image}")
                                        image(env.build_in_docker.image)
                                    } else if (env.build_in_docker.dockerfile != null) {
                                        out.println("${env.full_name} building in docker image from Dockerfile ${env.build_in_docker.dockerfile}")
                                        // FIXME!
                                        // pkcs11-proxy is the only one using this.
                                        // This can be done in pipeline, but in docker-cloud?
                                        //dockerfile('.', env.build_in_docker.dockerfile)
                                        out.println("Doesn't support Dockerfile yet, so use the regular image for now")
                                        image("docker.sunet.se/sunet/docker-jenkins-job")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            scm {
                git {
                    remote {
                        url("https://github.com/${env.repo_full_name}.git")
                    }
                    // Branch
                    if (env.git.branch != null) {
                        out.println("${env.full_name} building branch ${env.git.branch}")
                        branch(env.git.branch)
                    } else if (env.git.branches != null) {
                        out.println("${env.full_name} building branches ${env.git.branches}")
                        // Explicitly convert branches to class String[]
                        branches(env.git.branches as String[])
                    } else {
                        out.println("${env.full_name} building branch master")
                        branch("master")
                    }
                    // Extensions
                    if (env.git.extensions != null) {
                        extensions {
                            if (env.git.extensions.checkout_local_branch != null) {
                                out.println("${env.full_name} checking out local branch")
                                pruneBranches()
                                localBranch("**")
                            }
                            if (env.git.extensions.shallow_clone != null) {
                                cloneOptions {
                                    out.println("${env.full_name} doing shallow clone")
                                    shallow(true)
                                }
                            }
                        }
                    }
                }
            }
            triggers {
                // github_push is enabled by default
                if (_get_bool(env.triggers.github_push, true)) {
                    out.println("${env.full_name} using trigger github push")
                    githubPush()
                }
                if (env.triggers.cron != null) {
                    out.println("${env.full_name} using trigger cron: ${env.triggers.cron}")
                    cron(env.triggers.cron)
                }
                if (env.upstream != null && env.upstream.size() > 0) {
                    out.println("${env.full_name} using trigger upstream: ${env.upstream.join(', ')}")
                    upstream(env.upstream.join(', '))
                }
            }
            publishers {
                if (_slack_enabled(env)) {
                    out.println("${env.full_name} using Slack notification to: ${env.slack.room}")
                    slackNotifier {
                        teamDomain('SUNET')
                        tokenCredentialId('SLACK_TOKEN')
                        room(env.slack.room)
                        notifyAborted(true)
                        notifyFailure(true)
                        notifyNotBuilt(true)
                        notifyUnstable(true)
                        notifyBackToNormal(true)
                        notifySuccess(false)
                        notifyRepeatedFailure(true)
                        startNotification(false)
                        includeTestSummary(false)
                        includeCustomMessage(false)
                        customMessage(env.slack.custom_message)
                        commitInfoChoice('NONE')
                        sendAs(env.slack.sendas)
                    }
                }
                if (env.downstream != null && env.downstream.size() > 0) {
                    out.println("${env.full_name} using downstream ${env.downstream.join(', ')}")
                    downstream(env.downstream.join(', '))
                }
                if (env.publish_over_ssh != null) {
                    env.publish_over_ssh.each {
                        if (it == 'pypi.sunet.se') {
                            if (env.builders.contains("python") || env.builders.contains("script")) {
                                out.println("Publishing over ssh to ${it} enabled.")
                                publishOverSsh {
                                    alwaysPublishFromMaster(true)
                                    server('pypi.sunet.se') {
                                        transferSet {
                                            sourceFiles('dist/*.egg,dist/*.tar.gz,dist/*.whl')
                                            removePrefix('dist')
                                        }
                                    }
                                }
                            }
                        } else {
                            out.println("Don't know how to publish over ssh to ${it} for builders ${env.builders}.")
                        }
                    }
                }
                // Save artifacts for use in another project
                if (env.archive_artifacts != null) {
                    out.println("${env.full_name} using artifact archiver for ${env.archive_artifacts.include}")
                    artifactArchiver {
                        artifacts(env.archive_artifacts.include)
                        if (env.archive_artifacts.exclude != null) {
                            out.println("${env.full_name} excluding artifacts: ${env.archive_artifacts.exclude}")
                            excludes(env.archive_artifacts.exclude)
                        }
                        allowEmptyArchive(false)
                        onlyIfSuccessful(true)
                    }
                }
            }
            wrappers {
                // Clean workspace
                if (_get_bool(env.clean_workspace, false)) {
                    preBuildCleanup()
                }
                if (env.environment_variables != null) {
                    environmentVariables {
                        envs(env.environment_variables)
                    }
                }
                if (env.repo_must_be_sighed) {
                    // Provision stuff to check signature as first step in job.
                    credentialsBinding {
                        // Provision our keyring to validate against as a file on the agent
                        file('GNUPG_KEYRING', 'GNUPG_KEYRING')
                    }
                    configFiles {
                        // And provision our gpg-wrapper to use the keyring GNUPG_KEYRING
                        custom('GPG_WRAPPER') {
                            variable('GPG_WRAPPER')
                        }
                    }
                }
            }
            steps {
                if (env.repo_must_be_sighed) {
                    // Only run code which we can validate
                    shell('chmod +x "$GPG_WRAPPER" && git -c "gpg.program=$GPG_WRAPPER" verify-commit HEAD')
                }
                // Copy artifacts from another project
                if (env.copy_artifacts != null) {
                    out.println("Copy artifacts from ${env.copy_artifacts.project_name} configured")
                    copyArtifacts(env.copy_artifacts.project_name) {
                        if (env.copy_artifacts.target_dir != null) {
                            targetDirectory(env.copy_artifacts.target_dir)
                        }
                        if (env.copy_artifacts.include != null) {
                            includePatterns(env.copy_artifacts.include.join(', '))
                        }
                        if (env.copy_artifacts.exclude != null) {
                            excludePatterns(env.copy_artifacts.exclude.join(', '))
                        }
                        if (env.copy_artifacts.flatten != null) {
                            flatten(env.copy_artifacts.flatten)
                        }
                        if (env.copy_artifacts.optional != null) {
                            optional(env.copy_artifacts.optional)
                        }
                        buildSelector {
                            latestSuccessful(true)
                        }
                    }
                }
                // Pre-build script
                if (env.pre_build_script != null) {
                    shell(env.pre_build_script.join('\n'))
                    out.println('Pre-build script configured.')
                }
                // Mutually exclusive builder steps
                if (env.builders.contains("script")) {
                    shell(env.script.join('\n'))
                    out.println('Builder "script" configured.')
                } else if (env.builders.contains("make")) {
                    shell("make clean && make && make test")
                    out.println('Builder "make" configured.')
                } else if (env.builders.contains("cmake")) {
                    shell("/opt/builders/cmake")
                    out.println('Builder "cmake" configured.')
                } else if (env.builders.contains("python")) {
                    python_module = env.name
                    if (env.python_module != null) {
                        python_module = env.python_module
                    }
                    shell("/opt/builders/python ${python_module} ${env.python_source_directory}")
                    out.println('Builder "python" configured.')
                }
                // Builder docker
                if (env.builders.contains("docker")) {
                    if (_managed_script_enabled(env, 'docker_build_prep.sh')) {
                        out.println("Managed script docker_build_prep.sh enabled.")
                        managedScript('docker_build_prep.sh') {}
                    }
                    tags = ["git-\${GIT_REVISION,length=8}", "ci-${env.name}-\${BUILD_NUMBER}"]
                    if (env.docker_tags != null) {
                        tags.addAll(env.docker_tags)
                    }

                    if (_managed_script_enabled(env, 'docker_tag.sh')) {
                        out.println("Managed script docker_tag.sh enabled.")
                        out.println("Not using docker_tag.sh, having it done by dockerBuildAndPublish instead")
                        // docker_tag is buggy and trying to deterministically find a docker image
                        // based on a git sha. This detonates if it sees other images built on the same sha,
                        // so implement the same functionallity here.
                        tags.add("branch-\${GIT_BRANCH#origin/}")
                    }
                    if (!_get_bool(env.docker_skip_tag_as_latest, false))
                        tags.add("latest")

                    def full_names = []
                    for (tag in tags)
                        full_names.add("docker.sunet.se/${env.docker_name.replace("-/", "/")}:${tag}") // docker doesn't like glance-/repo, so mangle it to glance/repo

                    dockerBuilderPublisher {
                        dockerFileDirectory(env.docker_context_dir != null ? env.docker_context_dir : "")
                        tagsString(full_names.join("\n"))
                        pushOnSuccess(!is_dev_mode)
                        cloud("") // Use the current jobs cloud.
                        // Override where to pull from, and what credentials to use.
                        fromRegistry {
                            url('')
                            credentialsId('')
                        }
                        pushCredentialsId('')
                        cleanImages(true)
                        cleanupWithJenkinsJobDelete(true)
                    }
                    /* TODO: things not implemented in docker-plugin
                        forcePull(is_dev_mode ? false : _get_bool(env.docker_force_pull, true))
                        noCache(_get_bool(env.docker_no_cache, true))
                        forceTag(_get_bool(env.docker_force_tag, false))
                        createFingerprints(_get_bool(env.docker_create_fingerprints, true))
                    */
                    out.println('Builder "docker" configured.')
                }
                // Post-build script
                if (env.post_build_script != null) {
                    shell(env.post_build_script.join('\n'))
                    out.println('Post-build script configured.')
                }
            }
            logRotator {
                // Rotate builds
                numToKeep(10)
                // Rotate archived artifacts
                if (env.archive_artifacts != null) {
                    artifactNumToKeep(_get_int(env.archive_artifacts.num_to_keep, 1))
                }
            }
        }
    } else {
        out.println("No builder for ${env.full_name}... removing job")
    }
}

def orgs = ['SUNET','TheIdentitySelector']
def api = "https://api.github.com"

if (binding.hasVariable("ORGS") && "${ORGS}" != "") {
    orgs = new JsonSlurper().parseText("${ORGS}")
    out.println("orgs overridden by env: ${ORGS}")
}
def ONLY_REPOS = null
if (binding.hasVariable("REPOS") && "${REPOS}" != "") {
    ONLY_REPOS = new JsonSlurper().parseText("${REPOS}")
    out.println("repos overridden by env: ${REPOS}")
}
def is_dev_mode = false
if (binding.hasVariable("DEV_MODE") && "${DEV_MODE}" != "" && DEV_MODE.toBoolean()) {
    out.println("DEV_MODE detected, will act accordingly")
    is_dev_mode = true
}

def failure_in_repos = false

for (org in orgs) {
    //TODO: Should we set a per_page=100 (100 is max) to decrese the number of api calls,
    // So we don't get ratelimited as easy?
    def next_path = "/orgs/${org}/repos"
    def c = new URL(api + "/orgs/${org}").openConnection()
    // Is this a org?
    // If not, try the users endpoint
    if (c.getResponseCode() == 404) {
        out.println("${org} is not a org, guessing its a user")
        next_path = "/users/${org}/repos"
    }
    try {
        while (next_path != null) {
            // No, we can't use try_get_file here, because we need headers for links
            def url = new URL(api + next_path)
            def conn = url.openConnection()
            // This way you can add your own github auth token via https://github.com/settings/tokens
            // So you don't run into the request api request limit as quickly...
            if (binding.hasVariable("GITHUB_TOKEN") && "${GITHUB_TOKEN}" != "")
                conn.addRequestProperty("Authorization", "token ${GITHUB_TOKEN}")

            def repos = new JsonSlurper().parse(conn.getInputStream())

            // Terminate loop if we can't find a next link
            next_path = null
            Map<String, List<String>> headers = conn.getHeaderFields()
            if ('Link' in headers) {
                // The list isn't the Link's in the Link header,
                // its that it can be multiple Link headers in a response.
                def links = headers['Link'][0]
                //links.split(",").each { link ->
                for (link in links.split(",")) {
                    link = link.trim()
                    def m = (link =~ /<https:\/\/api.github.com([^>]+)>; rel="next"/)
                    if (m.matches()) {
                        next_path = m[0][1]
                        break;
                    }
                }
            }

            repos.each {
                def name = it.name
                def full_name = it.full_name.toLowerCase()
                if (ONLY_REPOS && !ONLY_REPOS.contains(name))
                    return // return is like continue in a closure
                out.println("repo: ${name}")
                try {
                    if (name != null && full_name != null && name != "null" && full_name != "null") {
                        env = load_env(it)
                        add_job(env, is_dev_mode)
                        if (env.extra_jobs != null) {
                            env.extra_jobs.each {
                                cloned_env = env.clone()  // No looping over changing data
                                cloned_env << it
                                out.println("found extra job: ${cloned_env.name}")
                                add_job(cloned_env, is_dev_mode)
                            }
                        }
                    }
                    out.println("---- EOJ ----")
                } catch (SignatureException ex) {
                    out.println("---- ${ex} - disabling job ${name} ----")
                    // Repo was flagged repo_must_be_signed, but signature verification failed.
                    // Disable job and do not update it if github doesn't think its signed
                    job(name) {
                        disabled()
                        description("Disabled due to ${ex}")
                        // Keep the rest of the configuration
                        using(name)
                    }
                    failure_in_repos = true
                } catch (RuntimeException ex) {
                    out.println("---- Failed to process ${name} ----")
                    out.println(ex.toString());
                    org.codehaus.groovy.runtime.StackTraceUtils.printSanitizedStackTrace(ex)
                    out.println("---- Trying next repo ----")
                    failure_in_repos = true
                }
            }
        }
    } catch (FileNotFoundException | IOException ex) {
        out.println("---- Bad response from: ----")
        out.println("Path: ${next_path}")
        out.println(ex.toString());
        out.println(ex.getMessage());
        throw ex
    }
}

for (managed_script in ["docker_build_prep.sh"]) {
    configFiles {
        scriptConfig {
            id(managed_script)
            name(managed_script)
            comment("Script managed from job-dsl, don't edit in jenkins.")
            content(readFileFromWorkspace("managed_scripts/" + managed_script))
        }
    }
}

listView("cnaas") {
    jobs {
        regex(/.*cnaas.*/)
    }
    columns {
        status()
        weather()
        name()
        lastSuccess()
        lastFailure()
        lastDuration()
        buildButton()
    }
}

listView("comanage") {
    jobs {
        regex(/^comanage.*/)
    }
    columns {
        status()
        weather()
        name()
        lastSuccess()
        lastFailure()
        lastDuration()
        buildButton()
    }
}

listView("eduid") {
    jobs {
        name("pysmscom")
        name("python-vccs_client")
        name("VCCS")
        regex(/.*eduid.*/)
    }
    columns {
        status()
        weather()
        name()
        lastSuccess()
        lastFailure()
        lastDuration()
        buildButton()
    }
}

listView("jenkins") {
    jobs {
        name("bootstrap-docker-builds")
        regex(/.*jenkins.*/)
    }
    columns {
        status()
        weather()
        name()
        lastSuccess()
        lastFailure()
        lastDuration()
        buildButton()
    }
}

listView("se-leg") {
    jobs {
        regex(/.*se-leg.*/)
    }
    columns {
        status()
        weather()
        name()
        lastSuccess()
        lastFailure()
        lastDuration()
        buildButton()
    }
}

if (failure_in_repos)
    SEED_JOB.getLastBuild().setResult(hudson.model.Result.UNSTABLE)
