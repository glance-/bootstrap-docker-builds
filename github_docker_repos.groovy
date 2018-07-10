@Grapes(
        @Grab(group = 'org.codehaus.groovy.modules.http-builder', module = 'http-builder', version = '0.6')
)
@Grapes(
        @Grab(group = 'org.codehaus.groovy', module = 'groovy-json', version = '2.4.12')
)
@Grapes(
        @Grab(group = 'org.jyaml', module = 'jyaml', version = '1.3')
)
import groovyx.net.http.HTTPBuilder
import groovy.json.JsonSlurper
import static groovyx.net.http.Method.GET
import static groovyx.net.http.ContentType.JSON
import org.ho.yaml.Yaml
import java.io.FileNotFoundException
import jenkins.model.Jenkins

def try_get_file(url) {
    return url.toURL().getText()
}

def _repo_file(full_name, branch, fn) {
    return "https://raw.githubusercontent.com/${full_name}/${branch}/${fn}"
}

def _is_disabled(env) {
    return env.disabled.toBoolean();
}

def _build_in_docker(env) {
    if (env.docker_disable != null && !env.docker_disable.toBoolean()) {
        out.println("${env.full_name} not building in docker. docker_disable: ${env.docker_disable}")
        return false
    } else if (env.builders.contains("docker")) {
        out.println("${env.full_name} not building in docker. \"docker\" in builders: ${env.builders}")
        return false
    } else if (env.builders.contains("multi-docker")) {
        out.println("${env.full_name} not building in docker. \"multi-docker\" in builders: ${env.builders}")
        return false
    }
    return true
}

def _slack_enabled(env) {
    if (env.slack.room != null) {
        if (env.slack.disabled == null || !env.slack.disabled.toBoolean()) {
            return true;
        }
    }
    return false;
}

def _managed_script_enabled(env, script_name) {
    if (env.managed_scripts != null && script_name in env.managed_scripts) {
        return true
    }
    return false
}

def load_env(repo) {
    def name = repo.name
    def full_name = repo.full_name.toLowerCase()

    // Default environment
    def env = [
            'name'                   : name,
            'full_name'              : full_name,
            'disabled'               : false,
            'python_source_directory': 'src',
            'slack'                  : ['room': 'devops', 'disabled': false],
            'triggers'               : [:],
            'builders'               : []
    ]

    // Load enviroment variables from repo yaml file
    try {
        env << Yaml.load(try_get_file(_repo_file(full_name, "master", ".jenkins.yaml")))
    } catch (FileNotFoundException ex) {
        out.println("No .jenkins.yaml for ${full_name}... will use defaults")
    }

    // detecting builders
    if (env.builder != null && env.builders.size() == 0) {
        env.builders += env.builder
    }

    // If builder or builders is empty try to guess
    if (env.builders == null || env.builders.size() == 0) {
        env.builders = []

        try {
            if (!name.equals("bootstrap-docker-builds") && try_get_file(_repo_file(full_name, "master", "Dockerfile"))) {
                env.builders += "docker"
            }

            if (env.docker_name == null) {
                env.docker_name = full_name
            }
        } catch (FileNotFoundException ex) { }

        try {
            if (try_get_file(_repo_file(full_name, "master", "setup.py")).contains("python")) {
                env.builders += "python"
            }
        } catch (FileNotFoundException ex) { }

        if (env.script != null) {
            env.builders += "script"
        }

        try {
            if (try_get_file(_repo_file(full_name, "master", "CMakeLists.txt"))) {
                env.builders += "cmake"
            }
        } catch (FileNotFoundException ex) { }
    }

    // detecting wrappers
    try {
        if (try_get_file(_repo_file(full_name, "master", "Dockerfile.jenkins")).contains("FROM")) {
            env.docker_file = "Dockerfile.jenkins"
        }
    } catch (FileNotFoundException ex) { }

    if (env.docker_file == null && env.docker_image == null) {
        env.docker_image = "docker.sunet.se/sunet/docker-jenkins-job"
    }

    return env
}

def add_job(env) {
    if (env.builders.size() > 0 && !_is_disabled(env)) {
        out.println("generating job for ${env.full_name} using builders: ${env.builders}")
        job(env.name) {
            scm {
                git("https://github.com/${env.full_name}.git", "master")
            }
            triggers {
                // github_push is enabled by default
                if (env.triggers.github_push == null || env.triggers.github_push.toBoolean()) {
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
                        authToken("${SLACK_TOKEN}".toString())
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
                if (env.jabber != null) {
                    out.println("${env.full_name} using Jabber notification to: ${env.jabber}")
                    publishJabber(env.jabber) {
                        strategyName('ANY_FAILURE')
                    }
                }
                if (env.downstream != null && env.downstream.size() > 0) {
                    out.println("${env.full_name} using downstream ${env.downstream.join(', ')}")
                    downstream(env.downstream.join(', '))
                }
                if (env.publish_over_ssh != null) {
                    env.publish_over_ssh.each {
                        if (it == 'pypi.sunet.se' && env.builders.contains("python")) {
                            out.println("Publishing over ssh to ${it} enabled.")
                            publishOverSsh {
                                alwaysPublishFromMaster(true)
                                server('pypi.sunet.se') {
                                    transferSet {
                                        sourceFiles('dist/*.egg,dist/*.tar.gz')
                                        removePrefix('dist')
                                    }
                                }
                            }
                        } else {
                            out.println("Don't know how to publish over ssh to ${it} for builders ${env.builders}.")
                        }
                    }
                }
            }

            wrappers {
                // Clean workspace
                if (env.clean_workspace != null && env.clean_workspace.toBoolean()) {
                    preBuildCleanup()
                }
                // Build in docker
                if (_build_in_docker(env) && (env.docker_image != null || env.docker_file != null)) {
                    buildInDocker {
                        forcePull(true);
                        if (env.docker_image != null) {
                            out.println("${env.full_name} building in docker image ${env.docker_image}")
                            image(env.docker_image)
                            // Enable docker in docker
                            volume('/usr/bin/docker', '/usr/bin/docker')
                            volume('/var/run/docker.sock', '/var/run/docker.sock')
                        } else if (env.docker_file != null) {
                            out.println("${env.full_name} building in docker image from Dockerfile ${env.docker_file}")
                            dockerfile('.', env.docker_file)
                            // Enable docker in docker
                            volume('/usr/bin/docker', '/usr/bin/docker')
                            volume('/var/run/docker.sock', '/var/run/docker.sock')
                        }
                    }
                }
            }
            steps {
                // Builder steps
                if (env.builders.contains("script")) {
                    out.println('Builder "script" used.')
                    shell(env.script.join('\n'))
                } else if (env.builders.contains("make")) {
                    out.println('Builder "make" used.')
                    shell("make clean && make && make test")
                } else if (env.builders.contains("cmake")) {
                    out.println('Builder "cmake" used.')
                    shell("/opt/builders/cmake")
                } else if (env.builders.contains("python")) {
                    out.println('Builder "python" used.')
                    python_module = env.name
                    if (env.python_module != null) {
                        python_module = env.python_module
                    }
                    shell("/opt/builders/python ${python_module} ${env.python_source_directory}")
                } else if (env.builders.contains("docker")) {
                    out.println('Builder "docker" used.')
                    if (_managed_script_enabled(env, 'docker_build_prep.sh')) {
                        out.println("Managed script docker_build_prep.sh enabled.")
                        managedScript('docker_build_prep.sh') {}
                    }
                    dockerBuildAndPublish {
                        repositoryName(env.docker_name)
                        dockerRegistryURL("https://docker.sunet.se")
                        tag("git-\${GIT_REVISION,length=8},ci-${env.name}-\${BUILD_NUMBER}")
                        forcePull(true)
                        noCache(true)
                        forceTag(false)
                        createFingerprints(true)
                    }
                    if (_managed_script_enabled(env, 'docker_tag.sh')) {
                        out.println("Managed script docker_tag.sh enabled.")
                        managedScript('docker_tag.sh') {}
                    }
                } else if (env.builders.contains("multi-docker")) {
                    out.println('Builder "multi-docker" used.')
                }
            }
        }
    } else {
        out.println("No builder for ${env.full_name}... removing job")
    }
}

def orgs = ['SUNET']
def url = "https://api.github.com/"

orgs.each {
    def next_path = "/orgs/${it}/repos"
    def next_query = null
    def api = new HTTPBuilder(url)

    while (next_path != null) {
        api.request(GET, JSON) { req ->
            uri.path = next_path
            if (next_query != null) {
                uri.query = next_query
            }
            headers.'User-Agent' = 'Mozilla/5.0'

            response.success = { resp, reader ->
                assert resp.status == 200

                def repos = reader
                next_path = null
                if (resp.headers.'Link' != null) {
                    resp.headers.'Link'.split(',').each {
                        it = it.trim()
                        def m = (it =~ /<https:\/\/api.github.com([^>]+)>; rel="next"/)
                        if (m.matches()) {
                            def a = m[0][1].split('\\?')
                            next_path = a[0]
                            next_query = null
                            if (a.length == 2) {
                                next_query = [:]
                                a[1].split('&').each {
                                    def av = it.split('=')
                                    next_query[av[0]] = av[1]
                                }
                            }
                        }
                    }
                }

                repos.each {
                    out.println("repo: ${it.name}")
                    def name = it.name
                    def full_name = it.full_name.toLowerCase()
                    if (name != null && full_name != null && name != "null" && full_name != "null") {
                        hudson.FilePath workspace = hudson.model.Executor.currentExecutor().getCurrentWorkspace()
                        env = load_env(it)
                        add_job(env)
                        cloned_env = env.clone()  // No looping over changing data
                        if (env.extra_jobs != null) {
                            env.extra_jobs.each {
                                cloned_env << it
                                out.println("found extra job: ${cloned_env.name}")
                                add_job(cloned_env)
                            }
                        }
                    }
                    out.println("---- EOJ ----")
                }
            }
        }
    }
}
