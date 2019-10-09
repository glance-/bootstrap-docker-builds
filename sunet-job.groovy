// vim: ts=4 sts=4 sw=4 et

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
    } catch (FileNotFoundException ex) {
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
        } catch (FileNotFoundException ex) { }

        try {
            if (try_get_file(_repo_file(env.repo_full_name, "master", "setup.py")).contains("python")) {
                echo("Found setup.py for ${env.full_name}. Adding \"python\" to builders.")
                env.builders += "python"
            }
        } catch (FileNotFoundException ex) { }

        if (env.script != null) {
            echo("script set for ${env.full_name}. Adding \"script\" to builders.")
            env.builders += "script"
        }

        try {
            if (try_get_file(_repo_file(env.repo_full_name, "master", "CMakeLists.txt"))) {
                echo("Found CMakeLists.txt for ${env.full_name}. Adding \"cmake\" to builders.")
                env.builders += "cmake"
            }
        } catch (FileNotFoundException ex) { }
    }

    // detecting wrappers
    try {
        if (try_get_file(_repo_file(env.repo_full_name, "master", "Dockerfile.jenkins")).contains("FROM")) {
            echo("Found Dockerfile.jenkins for ${env.full_name}. Will be used for build.")
            env.build_in_docker.dockerfile = "Dockerfile.jenkins"
        }
    } catch (FileNotFoundException ex) { }

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

// Save the real env, representing the enviorment variables from jenkins
def real_env = env

// load and parse .jenkins.yaml
def env = load_env()

// Run the extra-job bits if You're one of those
if (env.extra_jobs != null) {
    for (def job in env.extra_jobs) {
        if (job.name == JOB_BASE_NAME) {
            echo "I'm a extra-job"
            // Merge everything in the extra job over the current job
            env << job
            // And remove the extra_jobs bit, becase we're the extra job here,
            // And we shouldn't generate ourselfs.
            env.remove("extra_jobs")
            break;
        }
    }
}

if (env.builders.size() == 0 || _is_disabled(env)) {
    echo("No builder for ${env.full_name}...")
    currentBuild.result = "NOT_BUILT"
    return
}

echo("running job for ${env.full_name} using builders: ${env.builders}")

// Rotate builds
def log_rotator = [
    $class: "LogRotator",
    "numToKeepStr": '10',
]
// Rotate archived artifacts
if (env.archive_artifacts != null) {
    log_rotator["artifactNumToKeepStr"] = env.archive_artifacts.num_to_keep?.toString() ?: "1"
}
if (real_env.DEV_MODE?.toBoolean())
    echo "DEV_MODE detected"

def property_list = []
if (_build_in_docker(env)) {
    echo("${env.full_name} building in docker image ${env.build_in_docker.image}")
    def image
    if (env.build_in_docker.image != null) {
        echo("${env.full_name} building in docker image ${env.build_in_docker.image}")
        image = env.build_in_docker.image
    } else if (env.build_in_docker.dockerfile != null) {
        echo("${env.full_name} building in docker image from Dockerfile ${env.build_in_docker.dockerfile}")
        // FIXME!
        // pkcs11-proxy is the only one using this.
        // This can be done in pipeline, but in docker-cloud?
        //dockerfile('.', env.build_in_docker.dockerfile)
        echo("Doesn't support Dockerfile yet, so use the regular image for now")
        image = "docker.sunet.se/sunet/docker-jenkins-job"
    }
    property_list += [
        $class: "DockerJobTemplateProperty",
        template: [
            pullStrategy: real_env.DEV_MODE?.toBoolean() ? "PULL_NEVER" : (env.build_in_docker.force_pull ? "PULL_ALWAYS" : "PULL_LATEST"),
            // Name the container after what we build in it
            name: "docker-${env.full_name}",
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
                dockerCommand: env.build_in_docker.start_command,
                tty: true,
                image: image,
            ]
        ]
    ]
}

// github_push is enabled by default
def trigger_list = []
if (_get_bool(env.triggers.github_push, true)) {
    echo("${env.full_name} using trigger github push")
    trigger_list += githubPush()
}
if (env.triggers.cron != null) {
    echo("${env.full_name} using trigger cron: ${env.triggers.cron}")
    trigger_list += cron(env.triggers.cron)
}
if (env.upstream != null && env.upstream.size() > 0) {
    echo("${env.full_name} using trigger upstream: ${env.upstream.join(', ')}")
    trigger_list += upstream(env.upstream.join(', '))
}

// If we have some triggers, add them to the property-list
if (trigger_list)
    property_list += [pipelineTriggers(trigger_list)]

if (env.environment_variables != null) {
    for (def item in env.environment_variables) {
        // Set these variables in our current enviorment
        env[item.key] = item.value
    }
}
// We always need to keep FULL_NAME, and optionally DEV_MODE
property_list += [
    $class: 'EnvInjectJobProperty',
    info: [
        propertiesContent: "FULL_NAME=${FULL_NAME}\n" + (real_env.DEV_MODE != null ? "DEV_MODE=${DEV_MODE}\n" : "")
    ],
    keepBuildVariables: true,
    keepJenkinsSystemVariables: true,
    on: true
]

properties([
    buildDiscarder(log_rotator),
    [$class: 'GithubProjectProperty', projectUrlStr: "${env.full_name}"],
] + property_list)


def scmVars
node {
    // Generate our extra_jobs by running some job-dsl
    if (env.extra_jobs != null) {
        stage("extra_jobs") {
            def job_names = []
            for (job in env.extra_jobs) {
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
    try {
        stage("checkout") {
            def args = [
                $class: 'GitSCM',
                userRemoteConfigs: [[url: "https://github.com/${env.repo_full_name}.git"]],
                branches: [],
                extensions: [],
            ]
            // Branch
            if (env.git.branch != null) {
                echo("${env.full_name} building branch ${env.git.branch}")
                args["branches"].add(["name": "*/${env.git.branch}"])
            } else if (env.git.branches != null) {
                echo("${env.full_name} building branches ${env.git.branches}")
                for (def branch in env.git.branches) {
                    args["branches"].add(["name": "*/${branch}"])
                }
            } else {
                echo("${env.full_name} building branch master")
                args["branches"].add(["name": "*/master"])
            }
            if (env.git.extensions != null) {
                if (env.git.extensions.checkout_local_branch != null) {
                    echo("${env.full_name} checking out local branch")
                    args["extensions"].add([$class: 'PruneStaleBranch'])
                    args["extensions"].add([$class: 'LocalBranch'])
                }
                if (env.git.extensions.shallow_clone != null) {
                    args["extensions"].add([$class: 'CloneOption', shallow: true])
                }
            }
            scmVars = checkout(args)
            // ['GIT_BRANCH':'origin/master', 'GIT_COMMIT':'8408762af61447e38a832513e595a518d81bf9af', 'GIT_PREVIOUS_COMMIT':'8408762af61447e38a832513e595a518d81bf9af', 'GIT_PREVIOUS_SUCCESSFUL_COMMIT':'dcea3f3567b7f55bc7a1a2f3d6752c084cc9b694', 'GIT_URL':'https://github.com/glance-/docker-goofys.git']
        }
        if (env.copy_artifacts != null) {
            stage("Copy artifacts") {
                echo("Copy artifacts from ${env.copy_artifacts.project_name} configured")
                def args = [
                    projectName: env.copy_artifacts.project_name,
                    selector: lastSuccessful(),
                    fingerprintArtifacts: true,
                ]
                if (env.copy_artifacts.target_dir != null)
                    args["target"] = env.copy_artifacts.target_dir
                if (env.copy_artifacts.include != null)
                    args["filter"] = env.copy_artifacts.include
                if (env.copy_artifacts.exclude != null)
                    excludePatterns(env.copy_artifacts.exclude.join(', '))
                    args["excludes"] = env.copy_artifacts.exclude
                if (env.copy_artifacts.flatten != null)
                    args["flatten"] = env.copy_artifacts.flatten
                if (env.copy_artifacts.optional != null)
                    args["optional"] = env.copy_artifacts.optional
                copyArtifacts(args)
            }
        }
        if (env.pre_build_script != null) {
            stage("Pre build script") {
                sh(env.pre_build_script.join('\n'))
                echo('Pre-build script configured.')
            }
        }
        if (!env.builders.disjoint(["script", "make", "python"])) {
            stage("build script/make/python") {
                // Mutually exclusive builder steps
                if (env.builders.contains("script")) {
                    echo('Builder "script" configured.')
                    // This is expected to be run in the same shell,
                    // So enviorment-modifications carry over between lines in yaml.
                    sh(env.script.join('\n'))
                } else if (env.builders.contains("make")) {
                    echo('Builder "make" configured.')
                    sh("make clean && make && make test")
                } else if (env.builders.contains("cmake")) {
                    echo('Builder "cmake" configured.')
                    sh("/opt/builders/cmake")
                } else if (env.builders.contains("python")) {
                    echo('Builder "python" configured.')
                    def python_module = env.name
                    if (env.python_module != null) {
                        python_module = env.python_module
                    }
                    sh("/opt/builders/python ${python_module} ${env.python_source_directory}")
                }
            }
        }
        if (env.builders.contains("docker")) {
            stage("build docker") {
                if (_managed_script_enabled(env, 'docker_build_prep.sh')) {
                    echo("Managed script docker_build_prep.sh enabled.")
                    configFileProvider([configFile(fileId: 'docker_build_prep.sh', variable: 'DOCKER_BUILD_PREP')]) {
                        sh 'chmod +x "$DOCKER_BUILD_PREP" ; "$DOCKER_BUILD_PREP"'
                    }
                }
                tags = ["git-${scmVars.GIT_COMMIT[0..8]}", "ci-${env.name}-${BUILD_NUMBER}"]
                if (env.docker_tags != null)
                    tags.addAll(env.docker_tags)

                if (_managed_script_enabled(env, 'docker_tag.sh')) {
                    echo("Managed script docker_tag.sh enabled.")
                    echo("Not using docker_tag.sh, having it done by dockerBuildAndPublish instead")
                    // docker_tag is buggy and trying to deterministically find a docker image
                    // based on a git sha. This detonates if it sees other images built on the same sha,
                    // so implement the same functionallity here.
                    tags.add("branch-${scmVars.GIT_BRANCH.replace('origin/', '')}")
                }
                if (!_get_bool(env.docker_skip_tag_as_latest, false))
                    tags.add("latest")

                def full_names = []
                for (def tag in tags)
                    full_names.add("docker.sunet.se/${env.docker_name.replace("-/", "/")}:${tag}") // docker doesn't like glance-/repo, so mangle it to glance/repo

                def docker_build_and_publish = [
                    $class: 'DockerBuilderPublisher',
                    dockerFileDirectory: "",
                    tagsString: full_names.join("\n"),
                    pushOnSuccess: !real_env.DEV_MODE?.toBoolean(), // Don't push in dev mode
                ]
                if (env.docker_context_dir != null)
                    docker_build_and_publish["dockerFileDirectory"] = env.docker_context_dir
                /* No corresponding functionallity in docker-plugin
                dockerBuildAndPublish {
                    forcePull(false)
                    noCache(_get_bool(env.docker_no_cache, true))
                    forceTag(_get_bool(env.docker_force_tag, false))
                    createFingerprints(_get_bool(env.docker_create_fingerprints, true))
                }*/
                step(docker_build_and_publish)
                echo('Builder "docker" configured.')
            }
        }
        if (env.post_build_script != null) {
            stage("Post build script") {
                sh(env.post_build_script.join('\n'))
                echo('Post-build script configured.')
            }
        }
        if (env.downstream != null && env.downstream.size() > 0) {
            stage("Triggering downstreams") {
                echo("${env.full_name} using downstream ${env.downstream.join(', ')}")
                for (def downstream in env.downstream) {
                    build(job: downstream)
                }
            }
        }
        if (env.publish_over_ssh != null) {
            stage("Publishing over ssh") {
                for (def target in env.publish_over_ssh) {
                    if (target == 'pypi.sunet.se') {
                        if (env.builders.contains("python") || env.builders.contains("script")) {
                            echo("Publishing over ssh to ${it} enabled.")
                            sshPublisher(publishers: [sshPublisherDesc(
                                configName: 'pypi.sunet.se',
                                transfers: [sshTransfer(
                                    removePrefix: 'dist',
                                    sourceFiles: 'dist/*.egg,dist/*.tar.gz,dist/*.whl'
                                )]
                            )])
                        }
                    } else {
                        echo("Don't know how to publish over ssh to ${it} for builders ${env.builders}.")
                    }
                }
            }
        }
        if (env.archive_artifacts != null) {
            // Save artifacts for use in another project
            stage("Archiving artifacts") {
                echo("${env.full_name} using artifact archiver for ${env.archive_artifacts.include}")
                def args = [
                    "includes": env.archive_artifacts.include
                ]
                if (env.archive_artifacts.exclude != null) {
                    args["excludes"] = env.archive_artifacts.exclude
                }
                archive(args)
            }
        }
    } finally {
        if (_slack_enabled(env) && !real_env.DEV_MODE?.toBoolean()) {
            echo("${env.full_name} using Slack notification to: ${env.slack.room}")
            //slackSend "Build failed: - ${env.JOB_NAME} ${env.BUILD_NUMBER} (<${env.BUILD_URL}|Open>)"
            slackSend(channel: env.slack.room, message: env.slack.custom_message, tokenCredentialId: 'SLACK_TOKEN', username: env.slack.sendas)
        }
        if (env.jabber != null) {
            echo("${env.full_name} using Jabber notification to: ${env.jabber}")
            echo "No jabber plugin loaded"
        }
    }
}
