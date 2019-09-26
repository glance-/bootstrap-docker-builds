#!/bin/bash
#
# Make extra Docker tags after building a Docker image
#

set -e
set -x

# Locate the image just built. The COMMITTAG needs to match the Tag setting under
# "Docker Build and Publish" for the project (it should be 'git-${GIT_REVISION,length=8}')
COMMITTAG="git-${GIT_COMMIT:0:8}"
DOCKERNAME=$(docker images | grep "\s${COMMITTAG}\s" | awk '{print $1}')

test -n "${DOCKERNAME}"

IMAGE="${DOCKERNAME}:${COMMITTAG}"

#
# Make a tag for the Git branch this image was built from
#
# docker tag refuses the slash in origin/master - turn it into 'branch-master'
FIXED_BRANCH=$(echo "${GIT_BRANCH}" | sed -e 's/origin./branch-/')
test -n "${FIXED_BRANCH}"
docker tag "${IMAGE}" "${DOCKERNAME}:${FIXED_BRANCH}"
docker push "${DOCKERNAME}:${FIXED_BRANCH}"

#
# Make a tag with the 'git describe' of the image, which will include the Jenkins build number
#
GIT_DESCRIBE=$(git describe --always)
test -n "${GIT_DESCRIBE}"
FIXED_DESCRIBE=$(echo "${GIT_DESCRIBE}" | sed -e 's/^jenkins/ci/')
test -n "${FIXED_DESCRIBE}"
docker tag "${IMAGE}" "${DOCKERNAME}:${FIXED_DESCRIBE}"
docker push "${DOCKERNAME}:${FIXED_DESCRIBE}"

