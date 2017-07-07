@Grapes(
        @Grab(group='org.codehaus.groovy', module='http-builder' , version='0.4.0')
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
import org.jyaml.Yaml
import java.io.FileNotFoundException

def org = 'SUNET'
def url = "https://api.github.com/"
def next_path = "/orgs/${org}/repos"
def next_query = null
def api = new HTTPBuilder(url)
while (next_path != null) {
  api.request(GET,JSON) { req ->
    uri.path = next_path
    if (next_query != null) {
       out.println(next_query)
       uri.query = next_query
    }
    headers.'User-Agent' = 'Mozilla/5.0'

    response.success = { resp, reader ->
    out.println(resp)
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
       out.println(next_path)
    }

    repos.each {
      def name = it.name
      def full_name = it.full_name.toLowerCase()
      if (name != null && full_name != null) { 
      out.println("${name}")
      hudson.FilePath workspace = hudson.model.Executor.currentExecutor().getCurrentWorkspace()
      if (name.contains("docker") && !name.equals("bootstrap-docker-builds")) {
         job(name) {
            def env = ['slack':['room':'devops'],'triggers':[:]]
            def files = workspace.list()
            try {
               env << Yaml.load("https://raw.githubusercontent.com/${full_name}/master/.jenkins.yaml".toURL().getText())
            } catch (FileNotFoundException ex) {
               out.println(ex)
            }
            out.println("${env}")
            scm {
               git("https://github.com/${full_name}.git", "master")
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
               def docker_name = full_name
               if (env.docker_name != null) {
                   docker_name = env.docker_name
               }
               dockerBuildAndPublish {
                  repositoryName(docker_name)
                  dockerRegistryURL("https://docker.sunet.se")
                  tag("git-\${GIT_REVISION,length=8},ci-${name}-\${BUILD_NUMBER}")
                  forcePull(true)
                  forceTag(false)
                  createFingerprints(true)
               }
            }
         }
      }
      }
    }
  }
 }
}
