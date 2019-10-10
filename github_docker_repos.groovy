// vim: ts=4 sts=4 sw=4 et
import groovy.json.JsonSlurper
import java.io.IOException


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
            def url = new URL(api + next_path)
            def conn = url.openConnection()
            // This way you can add your own github auth token via https://github.com/settings/tokens
            // So you don't run into the request api request limit as quickly...
            //conn.addRequestProperty("Authorization", "token XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX")

            repos = new JsonSlurper().parse(conn.getInputStream())

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

            for (repo in repos) {
                if (repo.name.equals("bootstrap-docker-builds"))
                    continue
                if (ONLY_REPOS && !ONLY_REPOS.contains(repo.name))
                    continue
                out.println("repo: ${repo.name}")

                multibranchPipelineJob(repo.name) {
                    /*
                    properties {
                        githubProjectUrl("https://github.com/${repo.full_name}")
                    }*/
                    /*
                    environmentVariables {
                        env("FULL_NAME", repo.full_name)
                        env("DEV_MODE", is_dev_mode.toString())
                    }
                    */
                    /*
                    definition {
                        cps {
                            script(readFileFromWorkspace('sunet-job.groovy'))
                            sandbox()
                        }
                    }
                    */
                    triggers {
                        // This is the trigger to scan for new branches to build
                        periodicFolderTrigger {
                            interval("86400000") // Once a day
                        }
                    }
                    branchSources {
                        git {
                            id(repo.full_name)
                            remote("https://github.com/${repo.full_name}")
                        }
                    }
                    factory {
                        pipelineBranchDefaultsProjectFactory {
                            scriptId("sunet-job.groovy")
                            useSandbox(true)
                        }
                    }
                }
            }
        }
    } catch (IOException ex) {
        out.println("---- Bad response from: ----")
        out.println("Path: ${next_path}")
        out.println(ex.toString());
        out.println(ex.getMessage());
        throw ex
    }
}

configFiles {
    groovyScript {
        id("sunet-job.groovy")
        name("sunet-job.groovy")
        comment("Script managed from job-dsl, don't edit in jenkins.")
        content(readFileFromWorkspace("sunet-job.groovy"))
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
