import groovyx.net.http.HTTPBuilder
import static groovyx.net.http.Method.GET
import static groovyx.net.http.ContentType.JSON
  
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
