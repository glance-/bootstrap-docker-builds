// vim: ts=4 sts=4 sw=4 et
import java.io.IOException

// Used for merging .jenkins.yaml in to default env
def addNested(lhs, rhs) {
    rhs.each { k, v -> lhs[k] = lhs[k] in Map ? addNested(lhs[k], v) : v }
    return lhs
}

// https://stackoverflow.com/questions/7087185/retry-after-exception-in-groovy
def retry_get_file(int times = 5, Closure errorHandler = {e-> echo(e.message)}, Closure body) {
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
        return url.toURL().getText()
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
        echo("${env.full_name} not building in docker. build_in_docker.disable: ${env.build_in_docker.disable}")
        return false
    } else if (env.builders == ["docker"]) {
        echo("${env.full_name} not building in docker. \"docker\" is the only item in builders: ${env.builders}")
        return false
    } else if (env.build_in_docker.image == null && env.build_in_docker.dockerfile == null) {
        echo("${env.full_name} not building in docker. build_in_docker.mage: ${env.build_in_docker.image} and build_in_docker.dockerfile: ${env.build_in_docker.dockerfile}")
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

def load_env() {
    stage("load_env") {
        node {
            def scmVars = checkout(scm)
            echo scmVars.inspect().toString()
            echo scmVars.toString()
            def FULL_NAME = scmVars.GIT_URL.replace("https://github.com/", "")

            def _repo_file = { x, y, file -> return file }
            def try_get_file = { file -> return readFile(file) }

    // Default environment
    def env = [
        'name'                   : JOB_BASE_NAME,
        'full_name'              : FULL_NAME.toLowerCase(),
        'repo_full_name'         : FULL_NAME, // Jenkins is not case insensitive with push notifications
        'disabled'               : false,
        'git'                    : [:],
        'environment_variables'  : [:],
        'python_source_directory': 'src',
        'slack'                  : ['room': 'devops-builds', 'disabled': false],
        'triggers'               : [:],
        'builders'               : [],
        'build_in_docker'        : [
            'disabled': false,
            'dockerfile': null,
            'image': null,
            'start_command': "/run.sh"
        ]
    ]

    // Load enviroment variables from repo yaml file
    try {
        def yaml_text = try_get_file(_repo_file(env.repo_full_name, "master", ".jenkins.yaml"))
        // Mangle broken yaml into something a propper yaml parser stands
        def fixed_yaml_text = yaml_text.replaceAll('cron: (@\\w+)', 'cron: "$1"')
        if (yaml_text != fixed_yaml_text)
            echo("FIXME: This repo contains non compliant yaml")
        def repo_env = readYaml(text: fixed_yaml_text)
        env = addNested(env, repo_env)
    } catch (IOException|FileNotFoundException ex) {
        echo("No .jenkins.yaml for ${env.full_name}... will use defaults")
    }

    // detecting builders
    if (env.builder != null && env.builders.size() == 0) {
        echo("DEPRECATION WARNING. Use builders.")
        echo("Builder ${env.builder} found for ${env.full_name}, added to builders")
        env.builders += env.builder
    }

    // If builder or builders is empty try to guess
    if (env.builders == null || env.builders.size() == 0) {
        echo("No builders found for ${env.full_name}... trying to guess")
        env.builders = []

        try {
            if (try_get_file(_repo_file(env.repo_full_name, "master", "Dockerfile"))) {
                echo("Found Dockerfile for ${env.full_name}. Adding \"docker\" to builders.")
                env.builders += "docker"
            }

            if (env.docker_name == null) {
                echo("No docker_name set. Using ${env.full_name}.")
                env.docker_name = env.full_name
            }
        } catch (IOException|FileNotFoundException ex) { }

        try {
            if (try_get_file(_repo_file(env.repo_full_name, "master", "setup.py")).contains("python")) {
                echo("Found setup.py for ${env.full_name}. Adding \"python\" to builders.")
                env.builders += "python"
            }
        } catch (IOException|FileNotFoundException ex) { }

        if (env.script != null) {
            echo("script set for ${env.full_name}. Adding \"script\" to builders.")
            env.builders += "script"
        }

        try {
            if (try_get_file(_repo_file(env.repo_full_name, "master", "CMakeLists.txt"))) {
                echo("Found CMakeLists.txt for ${env.full_name}. Adding \"cmake\" to builders.")
                env.builders += "cmake"
            }
        } catch (IOException|FileNotFoundException ex) { }
    }

    // detecting wrappers
    try {
        if (try_get_file(_repo_file(env.repo_full_name, "master", "Dockerfile.jenkins")).contains("FROM")) {
            echo("Found Dockerfile.jenkins for ${env.full_name}. Will be used for build.")
            env.build_in_docker.dockerfile = "Dockerfile.jenkins"
        }
    } catch (IOException|FileNotFoundException ex) { }

    if (env.build_in_docker.dockerfile == null && env.build_in_docker.image == null) {
        echo("No explicit build in docker settings found for ${env.full_name}. Will use docker.sunet.se/sunet/docker-jenkins-job.")
        env.build_in_docker.image = "docker.sunet.se/sunet/docker-jenkins-job"
    } else {
        if (env.build_in_docker.dockerfile != null) {
            echo("Using dockerfile ${env.build_in_docker.dockerfile} to build ${env.full_name}.")
        } else {
            echo("Using image ${env.build_in_docker.image} to build ${env.full_name}.")
        }
    }

    return env
        }
    }
}

// load and parse .jenkins.yaml
def job_env = load_env()

// Run the extra-job bits if You're one of those
if (job_env.extra_jobs != null) {
    for (def job in job_env.extra_jobs) {
        if (job.name == JOB_BASE_NAME) {
            echo "I'm a extra-job"
            // Merge everything in the extra job over the current job
            job_env << job
            // And remove the extra_jobs bit, becase we're the extra job here,
            // And we shouldn't generate ourselfs.
            job_env.remove("extra_jobs")
            break;
        }
    }
}

if (job_env.builders.size() == 0 || _is_disabled(job_env)) {
    echo("No builder for ${job_env.full_name}...")
    currentBuild.result = "NOT_BUILT"
    return
}

echo("running job for ${job_env.full_name} using builders: ${job_env.builders}")

// Rotate builds
def log_rotator = [
    $class: "LogRotator",
    "numToKeepStr": '10',
]
// Rotate archived artifacts
if (job_env.archive_artifacts != null) {
    log_rotator["artifactNumToKeepStr"] = job_env.archive_artifacts.num_to_keep?.toString() ?: "1"
}

if (!('DEV_MODE' in env))
    env.DEV_MODE = 'true'

if (env.DEV_MODE?.toBoolean())
    echo "DEV_MODE detected"

def property_list = []
def docker_image = null
if (_build_in_docker(job_env)) {
    if (job_env.build_in_docker.image == "docker.sunet.se/sunet/docker-jenkins-job") {
        echo("Not specifically buidling in docker, because our image is docker.sunet.se/sunet/docker-jenkins-job")
    } else if (job_env.build_in_docker.image != null) {
        echo("${job_env.full_name} building in docker image ${job_env.build_in_docker.image}")
        docker_image = job_env.build_in_docker.image
    } else if (job_env.build_in_docker.dockerfile != null) {
        echo("${job_env.full_name} building in docker image from Dockerfile ${job_env.build_in_docker.dockerfile}")
        // FIXME!
        // pkcs11-proxy is the only one using this.
        // This can be done in pipeline, but in docker-cloud?
        //dockerfile('.', job_env.build_in_docker.dockerfile)
        echo("Doesn't support Dockerfile yet, so use the regular image for now")
        docker_image = "docker.sunet.se/sunet/docker-jenkins-job"
    }
    /* Buu, doesn't work. Use dockerNode() instead
    property_list += [
        $class: "DockerJobTemplateProperty",
        template: [
            pullStrategy: env.DEV_MODE?.toBoolean() ? "PULL_NEVER" : (job_env.build_in_docker.force_pull ? "PULL_ALWAYS" : "PULL_LATEST"),
            // Name the container after what we build in it
            name: "docker-${job_env.full_name}",
            connector: [
                attach: []
            ],
            labelString: '',
            instanceCapStr: '0',
            dockerTemplateBase: [
                $class: 'DockerTemplateBase',
                // Let global limit handle this
                // Enable docker in docker
                volumesString: \
                    '/usr/bin/docker:/usr/bin/docker:ro\n' +
                    '/var/run/docker.sock:/var/run/docker.sock',
                dockerCommand: job_env.build_in_docker.start_command,
                tty: true,
                image: docker_image,
            ]
        ]
    ]*/
}

// github_push is enabled by default
def trigger_list = []
if (_get_bool(job_env.triggers.github_push, true)) {
    echo("${job_env.full_name} using trigger github push")
    trigger_list += githubPush()
}
if (job_env.triggers.cron != null) {
    echo("${job_env.full_name} using trigger cron: ${job_env.triggers.cron}")
    trigger_list += cron(job_env.triggers.cron)
}
if (job_env.upstream != null && job_env.upstream.size() > 0) {
    echo("${job_env.full_name} using trigger upstream: ${job_env.upstream.join(', ')}")
    trigger_list += upstream(job_env.upstream.join(', '))
}

// If we have some triggers, add them to the property-list
if (trigger_list)
    property_list += [pipelineTriggers(trigger_list)]

if (job_env.job_environment_variables != null) {
    for (def item in job_env.job_environment_variables) {
        // Set these variables in our current job_enviorment
        job_env[item.key] = item.value
    }
}
// We always need to keep FULL_NAME, and optionally DEV_MODE
/*
property_list += [
    $class: 'EnvInjectJobProperty',
    info: [
        propertiesContent: "FULL_NAME=${FULL_NAME}\n" + (env.DEV_MODE != null ? "DEV_MODE=${DEV_MODE}\n" : "")
    ],
    keepBuildVariables: true,
    keepJenkinsSystemVariables: true,
    on: true
]
*/

properties([
    buildDiscarder(log_rotator),
    [$class: 'GithubProjectProperty', projectUrlStr: "${job_env.full_name}"],
] + property_list)


// This is broken out to a function, so it can be called either via a node() or a dockerNode()
def runJob(job_env) {
    // Generate our extra_jobs by running some job-dsl
    if (job_env.extra_jobs != null) {
        stage("extra_jobs") {
            def job_names = []
            for (job in job_env.extra_jobs) {
                job_names += job.name
            }
            jobDsl(
                failOnMissingPlugin: true,
                failOnSeedCollision: true,
                lookupStrategy: 'SEED_JOB',
                removedConfigFilesAction: 'DELETE',
                removedJobAction: 'DELETE',
                removedViewAction: 'DELETE',
                unstableOnDeprecation: true,
                scriptText: """
for (def extra_job in ${job_names.inspect()}) {
  pipelineJob("\${extra_job}") {
    using("${JOB_NAME}")
  }
}""")
        }
    }
    def scmVars
    try {
        stage("checkout") {
            def args = [
                $class: 'GitSCM',
                userRemoteConfigs: [[url: "https://github.com/${job_env.repo_full_name}.git"]],
                branches: [],
                extensions: [],
            ]
            // Branch
            if (job_env.git.branch != null) {
                echo("${job_env.full_name} building branch ${job_env.git.branch}")
                args["branches"].add(["name": "*/${job_env.git.branch}"])
            } else if (job_env.git.branches != null) {
                echo("${job_env.full_name} building branches ${job_env.git.branches}")
                for (def branch in job_env.git.branches) {
                    args["branches"].add(["name": "*/${branch}"])
                }
            } else {
                echo("${job_env.full_name} building branch master")
                args["branches"].add(["name": "*/master"])
            }
            if (job_env.git.extensions != null) {
                if (job_env.git.extensions.checkout_local_branch != null) {
                    echo("${job_env.full_name} checking out local branch")
                    args["extensions"].add([$class: 'PruneStaleBranch'])
                    args["extensions"].add([$class: 'LocalBranch'])
                }
                if (job_env.git.extensions.shallow_clone != null) {
                    args["extensions"].add([$class: 'CloneOption', shallow: true])
                }
            }
            scmVars = checkout(args)
            // ['GIT_BRANCH':'origin/master', 'GIT_COMMIT':'8408762af61447e38a832513e595a518d81bf9af', 'GIT_PREVIOUS_COMMIT':'8408762af61447e38a832513e595a518d81bf9af', 'GIT_PREVIOUS_SUCCESSFUL_COMMIT':'dcea3f3567b7f55bc7a1a2f3d6752c084cc9b694', 'GIT_URL':'https://github.com/glance-/docker-goofys.git']
            echo scmVars.inspect().toString()
            echo scmVars.toString()
            // FIXME: WTF?
            // Why is GIT_COMMIT missing , and only GIT_URL is there?
            scmVars.GIT_COMMIT = "0123456789ABCDEF"
        }
        if (job_env.copy_artifacts != null) {
            stage("Copy artifacts") {
                echo("Copy artifacts from ${job_env.copy_artifacts.project_name} configured")
                def args = [
                    projectName: job_env.copy_artifacts.project_name,
                    selector: lastSuccessful(),
                    fingerprintArtifacts: true,
                ]
                if (job_env.copy_artifacts.target_dir != null)
                    args["target"] = job_env.copy_artifacts.target_dir
                if (job_env.copy_artifacts.include != null)
                    args["filter"] = job_env.copy_artifacts.include
                if (job_env.copy_artifacts.exclude != null)
                    excludePatterns(job_env.copy_artifacts.exclude.join(', '))
                    args["excludes"] = job_env.copy_artifacts.exclude
                if (job_env.copy_artifacts.flatten != null)
                    args["flatten"] = job_env.copy_artifacts.flatten
                if (job_env.copy_artifacts.optional != null)
                    args["optional"] = job_env.copy_artifacts.optional
                copyArtifacts(args)
            }
        }
        if (job_env.pre_build_script != null) {
            stage("Pre build script") {
                sh(job_env.pre_build_script.join('\n'))
                echo('Pre-build script configured.')
            }
        }
        // Mutually exclusive builder steps
        if (job_env.builders.contains("script")) {
            stage("builder script") {
                echo('Builder "script" configured.')
                // This is expected to be run in the same shell,
                // So job_env-modifications carry over between lines in yaml.
                sh(job_env.script.join('\n'))
            }
        } else if (job_env.builders.contains("make")) {
            stage("builder make") {
                echo('Builder "make" configured.')
                sh("make clean && make && make test")
            }
        } else if (job_env.builders.contains("cmake")) {
            stage("builder cmake") {
                echo('Builder "cmake" configured.')
                sh("/opt/builders/cmake")
            }
        } else if (job_env.builders.contains("python")) {
            stage("builder python") {
                echo('Builder "python" configured.')
                def python_module = job_env.name
                if (job_env.python_module != null) {
                    python_module = job_env.python_module
                }
                sh("/opt/builders/python ${python_module} ${job_env.python_source_directory}")
            }
        }
        if (job_env.builders.contains("docker")) {
            stage("builder docker") {
                if (_managed_script_enabled(job_env, 'docker_build_prep.sh')) {
                    echo("Managed script docker_build_prep.sh enabled.")
                    configFileProvider([configFile(fileId: 'docker_build_prep.sh', variable: 'DOCKER_BUILD_PREP')]) {
                        sh 'chmod +x "$DOCKER_BUILD_PREP" ; "$DOCKER_BUILD_PREP"'
                    }
                }
                tags = ["git-${scmVars.GIT_COMMIT[0..8]}", "ci-${job_env.name}-${BUILD_NUMBER}"]
                if (job_env.docker_tags != null)
                    tags.addAll(job_env.docker_tags)

                if (_managed_script_enabled(job_env, 'docker_tag.sh')) {
                    echo("Managed script docker_tag.sh enabled.")
                    echo("Not using docker_tag.sh, having it done by dockerBuildAndPublish instead")
                    // docker_tag is buggy and trying to deterministically find a docker image
                    // based on a git sha. This detonates if it sees other images built on the same sha,
                    // so implement the same functionallity here.
                    tags.add("branch-${scmVars.GIT_BRANCH.replace('origin/', '')}")
                }
                if (!_get_bool(job_env.docker_skip_tag_as_latest, false))
                    tags.add("latest")

                def full_names = []
                for (def tag in tags)
                    full_names.add("docker.sunet.se/${job_env.docker_name.replace("-/", "/")}:${tag}") // docker doesn't like glance-/repo, so mangle it to glance/repo

                def docker_build_and_publish = [
                    $class: 'DockerBuilderPublisher',
                    dockerFileDirectory: "",
                    tagsString: full_names.join("\n"),
                    pushOnSuccess: !env.DEV_MODE?.toBoolean(), // Don't push in dev mode
                ]
                if (job_env.docker_context_dir != null)
                    docker_build_and_publish["dockerFileDirectory"] = job_env.docker_context_dir
                /* No corresponding functionallity in docker-plugin
                dockerBuildAndPublish {
                    forcePull(false)
                    noCache(_get_bool(job_env.docker_no_cache, true))
                    forceTag(_get_bool(job_env.docker_force_tag, false))
                    createFingerprints(_get_bool(job_env.docker_create_fingerprints, true))
                }*/
                step(docker_build_and_publish)
                echo('Builder "docker" configured.')
            }
        }
        if (job_env.post_build_script != null) {
            stage("Post build script") {
                sh(job_env.post_build_script.join('\n'))
                echo('Post-build script configured.')
            }
        }
        if (job_env.downstream != null && job_env.downstream.size() > 0) {
            stage("Triggering downstreams") {
                echo("${job_env.full_name} using downstream ${job_env.downstream.join(', ')}")
                for (def downstream in job_env.downstream) {
                    build(job: downstream, wait: false)
                }
            }
        }
        if (job_env.publish_over_ssh != null) {
            stage("Publishing over ssh") {
                for (def target in job_env.publish_over_ssh) {
                    if (target == 'pypi.sunet.se') {
                        if (job_env.builders.contains("python") || job_env.builders.contains("script")) {
                            echo("Publishing over ssh to ${target} enabled.")
                            sshPublisher(publishers: [sshPublisherDesc(
                                configName: 'pypi.sunet.se',
                                transfers: [sshTransfer(
                                    removePrefix: 'dist',
                                    sourceFiles: 'dist/*.egg,dist/*.tar.gz,dist/*.whl'
                                )]
                            )])
                        }
                    } else {
                        echo("Don't know how to publish over ssh to ${target} for builders ${job_env.builders}.")
                    }
                }
            }
        }
        if (job_env.archive_artifacts != null) {
            // Save artifacts for use in another project
            stage("Archiving artifacts") {
                echo("${job_env.full_name} using artifact archiver for ${job_env.archive_artifacts.include}")
                def args = [
                    "artifacts": job_env.archive_artifacts.include
                ]
                if (job_env.archive_artifacts.exclude != null) {
                    args["excludes"] = job_env.archive_artifacts.exclude
                }
                archiveArtifacts(args)
            }
        }
    } finally {
        if (_slack_enabled(job_env) && !env.DEV_MODE?.toBoolean()) {
            echo("${job_env.full_name} using Slack notification to: ${job_env.slack.room}")
            //slackSend "Build failed: - ${env.JOB_NAME} ${env.BUILD_NUMBER} (<${env.BUILD_URL}|Open>)"
            slackSend(channel: job_env.slack.room, message: job_env.slack.custom_message, tokenCredentialId: 'SLACK_TOKEN', username: job_env.slack.sendas)
        }
        if (job_env.jabber != null) {
            echo("${job_env.full_name} using Jabber notification to: ${job_env.jabber}")
            echo "No jabber plugin loaded"
        }
    }
}

ansiColor('xterm') {
    if (docker_image) {
        dockerNode(image: docker_image) {
            runJob(job_env)
        }
    } else {
        node() {
            runJob(job_env)
        }
    }
}
