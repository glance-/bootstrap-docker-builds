import groovyx.net.http.HTTPBuilder
import static groovyx.net.http.Method.GET
import static groovyx.net.http.Method.TEXT
  
def org = 'SUNET'
def url = "https://api.github.com/orgs/${org}/repos"

while (url) {
  def api = new HTTPBuilder(url)
  api.request(GET,TEXT) { req ->
    response.success = { resp, reader ->
    println(resp)
    assert resp.status == 200

    resp.headers.'Link'.split(',').each {
       m = it =~ /<(.+)>\s+rel=\"next\"/ 
       if (m) {
          url = m.group(0)
       }
    }

    def repos = new groovy.json.JsonSlurper().parse(reader)
    repos.each {
      def name = it.name
      out.println("${name}")
      if (name.contains("docker-satosa")) {
         job(name) {
            scm {
               git("https://github.com/${it.fullName}.git", "master")
            }
            steps {
               dockerBuildAndPublish {
                  repositoryName(it.fullName)
                  registry("https://docker.sunet.se")
                  tag('${BUILD_TIMESTAMP}_${GIT_REVISION,length=7}')
                  forcePull(true)
                  createFingerprint(true)
               }
            }
         }
      }
    }
  }
 }
}
