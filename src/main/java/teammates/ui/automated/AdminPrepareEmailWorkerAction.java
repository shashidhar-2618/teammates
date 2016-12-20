package teammates.ui.automated;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import teammates.common.datatransfer.AdminEmailAttributes;
import teammates.common.exception.TeammatesException;
import teammates.common.util.Assumption;
import teammates.common.util.Const;
import teammates.common.util.Const.ParamsNames;
import teammates.common.util.GoogleCloudStorageHelper;
import teammates.logic.core.AdminEmailsLogic;

import com.google.appengine.api.blobstore.BlobKey;
import com.google.apphosting.api.ApiProxy;

/**
 * Task queue worker action: prepares admin email to be sent via task queue.
 */
public class AdminPrepareEmailWorkerAction extends AutomatedAction {
    
    @Override
    protected String getActionDescription() {
        return null;
    }
    
    @Override
    protected String getActionMessage() {
        return null;
    }
    
    @Override
    public void execute() {
        String adminEmailTaskQueueMode = getRequestParamValue(ParamsNames.ADMIN_EMAIL_TASK_QUEUE_MODE);
        Assumption.assertNotNull(adminEmailTaskQueueMode);
        
        if (adminEmailTaskQueueMode.contains(Const.ADMIN_EMAIL_TASK_QUEUE_ADDRESS_MODE)) {
            
            log.info("Preparing admin email task queue in address mode...");
            
            String emailId = getRequestParamValue(ParamsNames.ADMIN_EMAIL_ID);
            Assumption.assertNotNull(emailId);
            
            String addressReceiverListString = getRequestParamValue(ParamsNames.ADMIN_EMAIL_ADDRESS_RECEIVERS);
            Assumption.assertNotNull(addressReceiverListString);
            
            addAdminEmailToTaskQueue(emailId, addressReceiverListString);
            
        } else if (adminEmailTaskQueueMode.contains(Const.ADMIN_EMAIL_TASK_QUEUE_GROUP_MODE)) {
            
            log.info("Preparing admin email task queue in group mode...");
            
            String emailId = getRequestParamValue(ParamsNames.ADMIN_EMAIL_ID);
            Assumption.assertNotNull(emailId);
            
            String groupReceiverListFileKey = getRequestParamValue(ParamsNames.ADMIN_EMAIL_GROUP_RECEIVER_LIST_FILE_KEY);
            Assumption.assertNotNull(groupReceiverListFileKey);
            
            String indexOfEmailListToResumeAsString =
                    getRequestParamValue(ParamsNames.ADMIN_GROUP_RECEIVER_EMAIL_LIST_INDEX);
            String indexOfEmailToResumeAsString = getRequestParamValue(ParamsNames.ADMIN_GROUP_RECEIVER_EMAIL_INDEX);
            
            int indexOfEmailListToResume = indexOfEmailListToResumeAsString == null
                                           ? 0
                                           : Integer.parseInt(indexOfEmailListToResumeAsString);
            int indexOfEmailToResume = indexOfEmailToResumeAsString == null
                                       ? 0
                                       : Integer.parseInt(indexOfEmailToResumeAsString);
            
            try {
                List<List<String>> processedReceiverEmails =
                        GoogleCloudStorageHelper.getGroupReceiverList(new BlobKey(groupReceiverListFileKey));
                addAdminEmailToTaskQueue(emailId, groupReceiverListFileKey, processedReceiverEmails,
                                         indexOfEmailListToResume, indexOfEmailToResume);
                
            } catch (IOException e) {
                log.severe("Unexpected error while adding admin email tasks: "
                           + TeammatesException.toStringWithStackTrace(e));
            }
            
        }
        
    }
    
    private boolean isNearDeadline() {
        
        long timeLeftInMillis = ApiProxy.getCurrentEnvironment().getRemainingMillis();
        return timeLeftInMillis / 1000 < 100;
    }
    
    private void addAdminEmailToTaskQueue(String emailId, String addressReceiverListString) {
        
        AdminEmailAttributes adminEmail = AdminEmailsLogic.inst().getAdminEmailById(emailId);
        Assumption.assertNotNull(adminEmail);
        List<String> addressList = new ArrayList<String>();
        
        if (addressReceiverListString.contains(",")) {
            addressList.addAll(Arrays.asList(addressReceiverListString.split(",")));
        } else {
            addressList.add(addressReceiverListString);
        }
        
        for (String emailAddress : addressList) {
            taskQueuer.scheduleAdminEmailForSending(emailId, emailAddress, adminEmail.getSubject(),
                                                    adminEmail.getContent().getValue());
        }
        
    }
    
    private void addAdminEmailToTaskQueue(String emailId, String groupReceiverListFileKey,
                                          List<List<String>> processedReceiverEmails,
                                          int indexOfEmailListToResume, int indexOfEmailToResume) {
        
        AdminEmailAttributes adminEmail = AdminEmailsLogic.inst().getAdminEmailById(emailId);
        Assumption.assertNotNull(adminEmail);
        
        log.info("Resume Adding group mail tasks for mail with id " + emailId + "from list index: "
                 + indexOfEmailListToResume + " email index: " + indexOfEmailToResume);
        
        int indexOfLastEmailList = 0;
        int indexOfLastEmail = 0;
        
        for (int i = indexOfEmailListToResume; i < processedReceiverEmails.size(); i++) {
            
            List<String> currentEmailList = processedReceiverEmails.get(i);
            
            for (int j = indexOfEmailToResume; j < currentEmailList.size(); j++) {
                String receiverEmail = currentEmailList.get(j);
                taskQueuer.scheduleAdminEmailForSending(emailId, receiverEmail, adminEmail.getSubject(),
                                                        adminEmail.getContent().getValue());
                
                if (isNearDeadline()) {
                    taskQueuer.scheduleAdminEmailPreparationInGroupMode(emailId, groupReceiverListFileKey, i, j);
                    log.info("Adding group mail tasks for mail with id " + emailId
                             + " have been paused with list index: " + i + " email index: " + j);
                    return;
                }
                
                indexOfLastEmail = j;
            }
            indexOfLastEmailList = i;
        }
        
        log.info("Adding Group mail tasks for mail with id " + emailId
                 + "was complete. List index : " + indexOfLastEmailList
                 + " Email index: " + indexOfLastEmail);
    }
    
}