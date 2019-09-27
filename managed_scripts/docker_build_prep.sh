#!/bin/bash
set -x
set -e

# revision.txt is updated on every build,
# used to enforce building of some but not all layers in Dockerfiles
test -d docker && (git describe --always > docker/revision.txt; git log -n 1 >> docker/revision.txt)

exit 0
