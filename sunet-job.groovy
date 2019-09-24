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

def env = load_env()
if (env.extra_jobs != null) {
    echo("Would have created extra jobs")
    /*
    env.extra_jobs.each {
        cloned_env = env.clone()  // No looping over changing data
        cloned_env << repo
        echo("found extra job: ${cloned_env.name}")
        add_job(cloned_env)
    }
    */
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
    log_rotator["artifactNumToKeepStr"] = _get_int(env.archive_artifacts.num_to_keep, 1)
}
if (real_env.DEV_MODE?.toBoolean())
    echo "DEV_MODE detected"

properties([[$class: 'GithubProjectProperty', projectUrlStr: "${env.full_name}"]])
def scmVars
pipeline {
    options {
        buildDiscarder(strategy: log_rotator)
    }
    /* FIXME:
    if (_build_in_docker(env)) {
        if (env.build_in_docker.image != null) {
            echo("${env.full_name} building in docker image ${env.build_in_docker.image}")
            agent {
                docker {
                    image(env.build_in_docker.image)
                    // Enable docker-in-docker
                    args("-v /usr/bin/docker:/usr/bin/docker:ro -v /var/run/docker.sock:/var/run/docker.sock -t '${env.build_in_docker.start_command}'")
                    alwaysPull(real_env.DEV_MODE?.toBoolean() ? false : env.build_in_docker.force_pull)
                }
            }
        } else if (env.build_in_docker.dockerfile != null) {
            echo("${env.full_name} building in docker image from Dockerfile ${env.build_in_docker.dockerfile}")
            dockerfile {
                filename(env.build_in_docker.dockerfile)
                // Enable docker-in-docker
                args("-v /usr/bin/docker:/usr/bin/docker:ro -v /var/run/docker.sock:/var/run/docker.sock -t '${env.build_in_docker.start_command}'")
            }
        } else {
            throw new Exception("Neither image or dockerfile!")
        }
    } else {
    */
        agent any
    //}
        /* FIXME:
    triggers {
        // github_push is enabled by default
        if (_get_bool(env.triggers.github_push, true)) {
            echo("${env.full_name} using trigger github push")
            githubPush()
        }
        // Workaround org.ho.yaml.Yaml bug that resolvs null to the string null
        if (env.triggers.cron != null && env.triggers.cron != "null") {
            echo("${env.full_name} using trigger cron: ${env.triggers.cron}")
            cron(env.triggers.cron)
        }
        if (env.upstream != null && env.upstream.size() > 0) {
            echo("${env.full_name} using trigger upstream: ${env.upstream.join(', ')}")
            upstream(env.upstream.join(', '))
        }
    }
        */
    /*
    environment {
        [*:env.environment_variables]
    }
    */
    stages {
        stage("checkout") {
            steps {
                script {
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
                        for (branch in env.git.branches) {
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
            }
        }
        stage("Copy artifacts") {
            when {
                expression { env.copy_artifacts != null }
            }
            steps {
                script {
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
        }
        // Pre-build script
        stage("Pre build script") {
            when { expression { env.pre_build_script != null } }
            steps {
                sh(env.pre_build_script.join('\n'))
                echo('Pre-build script configured.')
            }
        }
        stage("build script/make/python") {
            when { expression { !env.builders.disjoint(["script", "make", "python"]) } }
            steps {
                script {
                    // Mutually exclusive builder steps
                    if (env.builders.contains("script")) {
                        echo('Builder "script" configured.')
                        for (script in env.script) {
                            sh(script)
                        }
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
        }
        // Builder docker
        stage("build docker") {
            when { expression { env.builders.contains("docker") } }
            steps {
                script {
                    if (_managed_script_enabled(env, 'docker_build_prep.sh')) {
                        echo("Managed script docker_build_prep.sh enabled.")
                        configFileProvider([configFile(fileId: 'docker_build_prep.sh', variable: 'DOCKER_BUILD_PREP')]) {
                            sh '$DOCKER_BUILD_PREP'
                        }
                    }
                    tags = ["git-${scmVars.GIT_COMMIT[0..8]}", "ci-${env.name}-${BUILD_NUMBER}"]
                    if (env.docker_tags != null)
                        tags.addAll(env.docker_tags)

                    if (_managed_script_enabled(env, 'docker_tag.sh')) {
                        echo("Managed script docker_tag.sh enabled.")
                        //managedScript('docker_tag.sh') {}
                        echo("Not using docker_tag.sh, having it done by dockerBuildAndPublish.")
                        //docker_tag is buggy and detonates if it sees other images built on the same sha,
                        //so implement the same functionallity here.
                        tags.add("branch-${scmVars.GIT_BRANCH.replace('origin/', '')}")
                    }
                    if (!_get_bool(env.docker_skip_tag_as_latest, false))
                        tags.add("latest")

                    def full_names = []
                    for (tag in tags)
                        full_names.add("https://docker.sunet.se/${env.docker_name}:${tag}")

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
        }
        // Post-build script
        stage("Post build script") {
            when { expression { env.post_build_script != null } }
            steps {
                sh(env.post_build_script.join('\n'))
                echo('Post-build script configured.')
            }
        }
        stage("Triggering downstreams") {
            when { expression { env.downstream != null && env.downstream.size() > 0 } }
            steps {
                script {
                    echo("${env.full_name} using downstream ${env.downstream.join(', ')}")
                    for (downstream in env.downstream) {
                        build(job: downstream)
                    }
                }
            }
        }
        stage("Publishing over ssh") {
            when { expression { env.publish_over_ssh != null } }
            steps {
                script {
                    for (target in env.publish_over_ssh) {
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
        }
        // Save artifacts for use in another project
        stage("Archiving artifacts") {
            when { expression { env.archive_artifacts != null } }
            steps {
                script {
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
        }
    }
    post {
        unsuccessful {
            script {
                if (_slack_enabled(env)) {
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
        fixed {
            script {
                if (_slack_enabled(env)) {
                    echo("${env.full_name} using Slack notification to: ${env.slack.room}")
                    //slackSend "Build fixed: - ${env.JOB_NAME} ${env.BUILD_NUMBER} (<${env.BUILD_URL}|Open>)"
                    slackSend(channel: env.slack.room, message: env.slack.custom_message, tokenCredentialId: 'SLACK_TOKEN', username: env.slack.sendas)
                }
                if (env.jabber != null) {
                    echo("${env.full_name} using Jabber notification to: ${env.jabber}")
                    echo "No jabber plugin loaded"
                }
            }
        }
    }
}
