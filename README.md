
Bootstrap job for automatically creating docker jobs from github for SUNET

### Available settings
```groovy
// Default environment
def env = [
        'name'                   : name from github ex. eduid-am,
        'full_name'              : full name from github ex. sunet/eduid-am,
        'disabled'               : false,
        'python_source_directory': 'src',
        'slack'                  : ['room': 'devops', 'enable': true],
        'triggers'               : [:],
        'builders'               : []
    ]
```

#### .jenkins.yaml with default values
```yaml
# Does not add a job if set to true
disabled: false

# Settings for building in a docker container
build_in_docker:
  # Using docker as only builder will disable build in docker
  # Does not try to build in a docker container if set to true
  disable: false
  # Docker image to build inside of
  image: docker.sunet.se/sunet/docker-jenkins-job
  # First build a docker image to build inside of
  dockerfile: ~
  # Verbose output if set to true
  verbose: false

# String in builder will be added to the list builders
builder: ~
# Se below for available builders
builders: []
# Will set items in the list as upstream
upstream: []
# Will set items in the list as downstream
downstream: []
triggers:
  # Build when push received to the master branch
  github_push: true
  # Use cron syntax to periodically build project, eg. @daily
  cron: ~
slack:
  # Default to send errors and back to normal to the devops channel
  disabled: false
  room: "devops"
  custom_message: ~
  sendas: ~
# Jabber room to send updates to
jabber: ~
# If script is not empty the script builder will be used by default
# Every list item is a line
script: []
# Save artifacts from successful builds
archive_artifacts:
  # You can use wildcards like "module/dist/**/*.zip"
  include: "module/dist/**/*.zip"
  # Optionally specify the 'excludes' pattern, such as "foo/bar/**/*"
  exclude: "foo/bar/**/*"
  # Number of archives to keep
  num_to_keep: 1
# Use artifacts from another projects last successful build
copy_artifacts:
  # Name of project to copy artifacts from
  project_name: name_of_project
  # Directory to copy artifacts to. Artifact source dir will be used if omitted
  target_dir: ~
  # Relative path to artifacts to include, eg. module/dist/**/*.zip. All artifacts will be included if omitted
  include
    - **/*.zip
  # Relative path to artifacts to exclude
  exclude
    - **/*.xml
  # Ignores the directory structure of the artifacts
  flatten: false
  # Allows this build to continue even if no last successful build of the artifact project can be found
  optional: false

# Set to true if workspace should be removed before build
clean_workspace: false

# Settings for builder python
# Module name to be used in python builder script, defaults to project name
python_module: ~
# Source directory for use in python builder script
python_source_directory: src
# The only supported value is pypi.sunet.se that uploads *.egg and *.tar.gz from ./dist
# Will check that builder python is used
publish_over_ssh: ~

# Settings for builder docker
# Name that built docker image should have
docker_name: sunet/name_of_repo
# Set docker context directory if different from repo root
docker_context_dir: ~
# Use managed script in the list where applicable
managed_scripts: []

# Define extra jobs derived from this projects settings
extra_jobs: []
```

#### Available builders
```yaml
builders:
  # Runs "make clean && make && make test" in the default job container
  - make
  # Runs the script /opt/builders/cmake in the default job container
  - cmake
  # Runs the script /opt/builders/python in the default job container
  # Arguments are env.name|env.python_module env.python_source_directory
  - python
  # Will build and push a docker image to docker.sunet.se
  # Expects the Dockerfile to be in the project root, can be change by setting docker_context_dir
  # If managed_scripts docker_build_prep.sh and/or docker_tag.sh is set those will be used
  - docker
```

#### Extra jobs
```yaml
# An example of an extra job
# eduid-am is Python project repo and we want a docker image built after
# python code succefully finishes
# The options from outside extra_jobs file will be used unless explicitly set in the extra job
extra_jobs:
  - name: eduid-am-docker
    builders:
      - docker
    docker_name: eduid/eduid-am
    managed_scripts:
      - docker_build_prep.sh
      - docker_tag.sh
    triggers:
      github_push: false
      cron: ~
    upstream:
      - eduid-am
```

### Tips
#### Inconsistency detected by ld.so
If the a new build fails with an error message saying something about "Inconsistency detected by ld.so" use `unset LD_LIBRARY_PATH` before script build command.

