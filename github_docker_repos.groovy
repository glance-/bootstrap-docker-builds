import groovyx.net.http.HTTPBuilder
import groovy.json.JsonSlurper
import static groovyx.net.http.Method.GET
import static groovyx.net.http.ContentType.JSON
import org.ho.yaml.Yaml

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
      if (name.contains("docker-satosa")) {
         job(name) {
            def env = System.getenv()
            env << ['slack':['room':'devops']]
            def files = workspace.list()
            if (files.contains('.jenkins.json')) {
               env << (new JsonSlurper()).parseText(streamFileFromWorkspace('.jenkins.json'))
            }
            if (files.contains('.jenkins.yaml')) {
               env << Yaml.load(streamFileFromWorkspace('.jenkins.yaml'))
            }
            scm {
               git("https://github.com/${full_name}.git", "master")
            }
            triggers {
               githubPush()
               if (env['schedule'] != null) {
                  cron(env['schedule'])
               }
            }
            publishers {
               slackNotifier {
                  teamDomain('SUNET')
                  authToken(env['SLACK_TOKEN'])
                  room(env.slack.room)
                  notifyAborted(true)
                  notifyFailure(true)
                  notifyNotBuilt(true)
                  notifyUnstable(true)
                  notifyBackToNormal(true)
                  notifySuccess(false)
                  notifyRepeatedFailure(false)
                  startNotification(false)
                  includeTestSummary(false)
                  includeCustomMessage(false)
                  customMessage(env.slack.custom_message)
                  buildServerUrl("https://ci.sunet.se")
                  commitInfoChoice('NONE')
                  sendAs(env.slack.sendas)
               }
               if (env['jabber'] != null) {
                  publishJabber(env['jabber']) {
                     strategyName('ANY_FAILURE')
                  }
               }
            }
            steps {
               dockerBuildAndPublish {
                  repositoryName(full_name)
                  dockerRegistryURL("https://docker.sunet.se")
                  tag("git-\${GIT_REVISION,length=8},ci-${name}-\${BUILD_NUMBER}")
                  forcePull(true)
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
