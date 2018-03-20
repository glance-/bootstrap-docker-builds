@Grapes(
        @Grab(group='org.codehaus.groovy.modules.http-builder', module='http-builder' , version='0.6')
)
@Grapes(
        @Grab(group='org.codehaus.groovy', module='groovy-json' , version='2.4.12')
)
@Grapes(
        @Grab(group='org.jyaml', module='jyaml', version='1.3')
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

def _repo_file(full_name,branch,fn) {
   return "https://raw.githubusercontent.com/${full_name}/${branch}/${fn}"
}

def load_env(repo) {
   def name = repo.name
   def full_name = repo.full_name.toLowerCase()

   def env = ['slack':['room':'devops'],'triggers':[:],'name':name,'full_name':full_name,'builders': []]
   try {
      env << Yaml.load(try_get_file(_repo_file(full_name,"master",".jenkins.yaml")))
   } catch (FileNotFoundException ex) {
      out.println("No .jenkins.yaml for ${full_name}...")
   }

   if (env.builders == null || env.builders.size() == 0) {
      env.builders = []
      if (name.contains("docker") && !name.equals("bootstrap-docker-builds")) {
         env.builders += "docker"
         if (env.docker_name == null) {
            env.docker_name = full_name
         }
      }

      try {
         if (try_get_file(_repo_file(full_name,"master","setup.py")).contains("python")) {
            env.builders += "python"
         }
      } catch (FileNotFoundException ex) { }

      if (env.script != null) {
         env.builders += "script"
      }
   }

   return env
}

def add_job(env) {
    if (env.builders.size() > 0) {
        out.println("generating job for ${env.full_name} using builders: ${env.builders}")
        job(env.name) {
            scm {
                git("https://github.com/${env.full_name}.git", "master")
            }
            triggers {
                githubPush()
                if (env.triggers.cron != null) {
                   cron(env.triggers.cron)
                }
            }
            publishers {
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
                if (env.jabber != null) {
                   publishJabber(env.jabber) {
                      strategyName('ANY_FAILURE')
                   }
                }
            }
            steps {
                if (env.builders.contains("script")) {
                    shell(env.script)
                } else if (env.builders.contains("make")) {
                    shell("make && make test")
                } else if (env.builders.contains("python")) {
                    virtualenv {
                        name('venv')
                        command('test -f requirements.txt && pip install -r requirements.txt')
                        command('test -f test_requirements.txt && pip install -r test_requirements.txt')
                        command('pip install nose coverage')
                        command('python setup.py install')
                        command('python setup.py test')
                        clear()
                    }
                } else if (env.builders.contains("docker")) {
                   dockerBuildAndPublish {
                      repositoryName(env.docker_name)
                      dockerRegistryURL("https://docker.sunet.se")
                      tag("git-\${GIT_REVISION,length=8},ci-${env.name}-\${BUILD_NUMBER}")
                      forcePull(true)
                      forceTag(false)
                      createFingerprints(true)
                   }
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
      api.request(GET,JSON) { req ->
        uri.path = next_path
        if (next_query != null) {
           uri.query = next_query
        }
        headers.'User-Agent' = 'Mozilla/5.0'

        response.success = { resp, reader ->
            assert resp.status == 200

            def repos = reader
            next_path = null
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

            repos.each {
                out.println("repo: ${it.name}")
                def name = it.name
                def full_name = it.full_name.toLowerCase()
                if (name != null && full_name != null && name != "null" && full_name != "null") {

                    hudson.FilePath workspace = hudson.model.Executor.currentExecutor().getCurrentWorkspace()
                    env = load_env(it)
                    add_job(env)
                }
                out.println("---- EOJ ----")
            }
        }
      }
    }
}