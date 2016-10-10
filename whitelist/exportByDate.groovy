@Grab(group = 'org.codehaus.groovy.modules.http-builder', module = 'http-builder', version = '0.7.2')
import groovyx.net.http.RESTClient
import groovyx.net.http.HttpResponseException


def query = 'items.find({"type":"file","created" : {"$gt" : "2016-08-16T19:20:30.45+01:00"},"repo" : "generic-local-archived"})' // replace this with your AQL query
def artifactoryURL = 'http://localhost:8081/artifactory/' // replace this with your Artifactory server
//def newRepotoMove = 'generic-local-archived' // replace this with your Artifactory server
def restClient = new RESTClient(artifactoryURL)
restClient.setHeaders(['Authorization': 'Basic ' + "admin:password".getBytes('iso-8859-1').encodeBase64()]) //replace the 'admin:password' with your own credentials
def dryRun = false //set the value to false if you want the script to actually delete the artifacts

def itemsToExport = getAqlQueryResult(restClient, query)
if (itemsToExport != null && itemsToExport.size() > 0) {
    download(restClient, itemsToExport, dryRun)
} else {
    println('Nothing to move')
}

/**
 * Send the AQL to Artifactory and collect the response.
 */
public List getAqlQueryResult(RESTClient restClient, String query) {
    def response
    try {
        response = restClient.post(path: 'api/search/aql',
                body: query,
                requestContentType: 'text/plain'
        )
    } catch (Exception e) {
        println(e.message)
    }
    if (response != null && response.getData()) {
        def results = [];
        response.getData().results.each {
            results.add(constructPath(it))
        }
        return results;
    } else return null
}

/**
 * Construct the full path form the returned items.
 * If the path is '.' (file is on the root) we ignores it and construct the full path from the repo and the file name only
 */
public constructPath(groovy.json.internal.LazyMap item) {
    if (item.path.toString().equals(".")) {
        return item.repo + "/" + item.name
    }
    return item.repo + "/" + item.path + "/" + item.name
}

/**
 * Send DELETE request to Artifactory for each one of the returned items
 */
public move(RESTClient restClient, List itemsToExport, def dryRun) {
    def response
    dryMessage = (dryRun) ? "*** This is a dry run ***" : "";
    itemsToExport.each {
        println("Trying to move artifact: '$it'")
        try {
            if (!dryRun) {
                respone = restClient.post(path:'api/move/'+String.valueOf(it), query : [to:'generic-local-scan'])
            }
            println("Artifact '$it' has been successfully moved. $dryMessage")
        } catch (HttpResponseException e) {
            println("Cannot move artifact '$it': $e.message" +
                    ", $e.statusCode")
        }// catch (HttpHostConnectException e) {
         //   println("Cannot move artifact: $e.message")
        //}
    }

}

public download(RESTClient restClient, List itemsToExport, def dryRun) {
    def date = demoFormat()
    def folder = "$date"
    def dir = "/Users/shanil/Documents/Artifactory/artifactory-pro-4.8.2/etc/plugins"
    def file = new File("$dir/$folder");
    file.mkdirs();
    dryMessage = (dryRun) ? "*** This is a dry run ***" : "";
    itemsToExport.each {
        println("Trying to download artifact: '$it'")
        try {
            if (!dryRun) {
                def response = restClient.get(path:String.valueOf(it))
                if (response.status == 200) {
                    String s = it.toString().substring(it.toString().indexOf("/") + 1)
                    file = new File("$dir/$folder/"+s)
                    file << response.getData()
                    println("Artifact '$it' has been successfully downloaded. $dryMessage")
                }
                else
                    println("response status: '$response.status'")
            }
        } catch (HttpResponseException e) {
            println("Cannot download artifact '$it': $e.message" +
                    ", $e.statusCode")
        }// catch (HttpHostConnectException e) {
        //   println("Cannot move artifact: $e.message")
        //}
    }

}

def String demoFormat()
{
    def now = new Date()
    def dateString = now.format("yyyy-MMM-dd")
    println "Formatted Now: ${dateString}"
    return dateString
}
