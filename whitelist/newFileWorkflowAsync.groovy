import org.artifactory.exception.CancelException
import org.artifactory.fs.ItemInfo
import org.artifactory.mime.MavenNaming
import org.artifactory.repo.RepoPath
@Grab('org.codehaus.groovy.modules.http-builder:http-builder:0.7' )
import groovyx.net.http.HTTPBuilder
import static groovyx.net.http.ContentType.TEXT
import static groovyx.net.http.Method.POST
import static WorkflowStatuses.*
import static com.google.common.collect.Multimaps.forMap
import java.nio.file.Path
import static Constants.*

/**
 * Execute an asynchronous workflow each time a new file is saved
 * based on newFileWorkflow.groovy
 */

enum WorkflowStatuses {
    NEW, // State of the artifact as it is created in Artifactory
    PENDING, // State of all new artifacts just before being send to the execute command
    PASSED, // State of artifacts already executed and test passed
    FAILED, // State of artifacts already executed and test failed
    FAILED_EXECUTION, // State of artifacts where execute command failed

    //send name or content to the scanning application
    ARTIFACT_NAME,
    ARTIFACT_CONTENT

    static final WORKFLOW_STATUS_PROP_NAME = 'workflow.status'
    static final WORKFLOW_RESULT_PROP_NAME = 'workflow.error'
}

class Constants {
    static final String SERVICE_URL = 'http://localhost:8942/webapi/myresource'
    static final String TEMP_FILE_PATH = "/Users/shanil/Documents/"
}

boolean applyTo(ItemInfo item) {
    // Add the code to filter the kind of element the workflow applies to
    // Following Example: All non pom or metadata files saved in a local repository
    RepoPath repoPath = item.repoPath
    // Activate workflow only on actual local artifacts not pom or metadata
    !item.folder && //!isRemote(repoPath.repoKey) && //definitely want to do this on cache repos
        !MavenNaming.isMavenMetadata(repoPath.path) &&
        !MavenNaming.isPom(repoPath.path)
}


void dummyExecute(RepoPath repoPath, WorkflowStatuses state) {

    String url = SERVICE_URL;
    String post = " -d \"";
    String path = repoPath.toString().replaceAll("[:]", "/");
    def http = new HTTPBuilder(url);

    if (state == ARTIFACT_NAME) {
        log.info "starting name"
        String curlargs = "curl -X POST -H \"Content-Type: text/plain\" ";
        String command = curlargs + url + post + path + "\"";

        http.request(POST, TEXT) {
            body = path
            response.success = {
                log.info "Workflow Plugin successfully posted to API"
            }
        }
    }
    else {
        log.info "starting content"
        String curlargs = "curl -X POST -H \"Content-Type: application/octet-stream\" ";
        String s = repoPath.toString();
        int pos = s.lastIndexOf(".");
        String x = path.substring(pos + 1, path.length());
        File currentTempFile = new File(TEMP_FILE_PATH + "/current." + x);
        String command = curlargs + url + post + currentTempFile + " " + url;

        InputStream is = repositories.getContent(repoPath).inputStream;

        copyInputStreamToFile(is, currentTempFile);

        log.info("Workflow send file for scanning");

        http.request(POST, 'application/octet-stream') { req ->
            body = currentTempFile.bytes
            response.success = {
                log.info "Workflow Plugin successfully posted to API"
            }
        }

        if(currentTempFile.delete()){
            System.out.println(currentTempFile.getName() + " is deleted!");
        }else{
            System.out.println("Delete operation is failed.");
        }
    }

}

storage {
    afterCreate { ItemInfo item ->
        try {
            if (applyTo(item)) {
                log.info ("Found artifact that needs work")
                setWorkflowStatus(item.repoPath, NEW)
                workflowExecute(item.repoPath)
            }
        } catch (Exception e) {
	    log.error("Workflow plugin could not set property on $item", e)
        }
    }
}

private void copyInputStreamToFile(InputStream input, File file) {
    try {
        OutputStream out = new FileOutputStream(file);
        byte[] buf = new byte[1024];
        int len;
        while((len=input.read(buf))>0){
            out.write(buf,0,len);
        }
        out.close();
        input.close();
    } catch (Exception e) {
        e.printStackTrace();
    }
}

private void workflowExecute(RepoPath newArtifact) {
    log.debug "Workflow plugin found artifact ${newArtifact.getName()} that needs work"
    setWorkflowStatus(newArtifact, PENDING)
    // Execute command
    try {
        dummyExecute(newArtifact, ARTIFACT_NAME)
    } catch (Exception e) {
        log.info("Workflow exception caught for name", e)
        setWorkflowError(newArtifact, FAILED_EXECUTION, e.getMessage())
    }

    try {
        dummyExecute(newArtifact, ARTIFACT_CONTENT)
    } catch (Exception e) {
        log.info("Workflow exception caught for content", e)
        setWorkflowError(newArtifact, FAILED_EXECUTION, e.getMessage())
    }
}


private void setWorkflowStatus(RepoPath repoPath, WorkflowStatuses status) {
    log.info "Workflow plugin setting ${WORKFLOW_STATUS_PROP_NAME}=${status} on ${repoPath.getId()}"
    repositories.setProperty(repoPath, WORKFLOW_STATUS_PROP_NAME, status.name())
}

private void setWorkflowError(RepoPath repoPath, WorkflowStatuses status, String result) {
    setWorkflowStatus(repoPath, status)
    log.info "Workflow plugin setting ${WORKFLOW_RESULT_PROP_NAME}=${result} on ${repoPath.getId()}"
    repositories.setProperty(repoPath, WORKFLOW_RESULT_PROP_NAME, result)
}

def isRemote(String repoKey) {
    if (repoKey.endsWith('-cache')) repoKey = repoKey.substring(0, repoKey.length() - 6)
    return repositories.getRemoteRepositories().contains(repoKey)
}

