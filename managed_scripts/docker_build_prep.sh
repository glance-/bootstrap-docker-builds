#!/bin/bash
set -x
set -e

git status

ls -l

pwd

# revision.txt is updated on every build,
# used to enforce building of some but not all layers in Dockerfiles
test -d docker && (git describe > docker/revision.txt; git log -n 1 >> docker/revision.txt)

GIT_BRANCH=$(echo ${GIT_BRANCH} | sed -e 's/origin./git-/')
export GIT_BRANCH
echo "$0: Set Git branch to ${GIT_BRANCH}"

exit 0

