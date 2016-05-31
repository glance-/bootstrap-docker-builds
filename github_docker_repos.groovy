import groovyx.net.http.HTTPBuilder
import static groovyx.net.http.Method.GET
import static groovyx.net.http.ContentType.JSON

  
def org = 'SUNET'
def url = "https://api.github.com/"
def api = new HTTPBuilder(url)
def next_path = "/orgs/${org}/repos"
while (url) {
  api.request(GET,JSON) { req ->
    uri.path = next_path
    headers.'User-Agent' = 'Mozilla/5.0'

    response.success = { resp, repos ->
    out.println(resp)
    assert resp.status == 200

    resp.headers.'Link'.split(',').each {
       out.println(it)
       def m = (it =~ /<https:\/\/api.github.com(\s+)>\S+rel=\"next\"/)
       if (m.matches()) {
          next_path = m.group(0)
       }
    }

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
