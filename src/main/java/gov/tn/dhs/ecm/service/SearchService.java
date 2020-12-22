package gov.tn.dhs.ecm.service;

import com.box.sdk.*;
import gov.tn.dhs.ecm.config.AppProperties;
import gov.tn.dhs.ecm.model.CitizenMetadata;
import gov.tn.dhs.ecm.model.FileInfo;
import gov.tn.dhs.ecm.model.SearchRequest;
import gov.tn.dhs.ecm.model.SearchResult;
import gov.tn.dhs.ecm.util.ConnectionHelper;
import gov.tn.dhs.ecm.util.JsonUtil;
import org.apache.camel.Exchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
public class SearchService extends BaseService {

    private static final Logger logger = LoggerFactory.getLogger(SearchService.class);

    private final AppProperties appProperties;

    private static final String CITIZEN_METADATA_TEMPLATE = "CitizenFolderMetadataTemplate";
    private static final String CITIZEN_METADATA_SCOPE = "enterprise";

    public SearchService(ConnectionHelper connectionHelper, AppProperties appProperties) {
        super(connectionHelper);
        this.appProperties = appProperties;
    }

    public void process(Exchange exchange) {
        SearchRequest searchRequest = exchange.getIn().getBody(SearchRequest.class);
        String appUserId = searchRequest.getAppUserId();
        long offset = searchRequest.getOffset();
        long limit = searchRequest.getLimit();
        logger.info("Search request received with payload {}", JsonUtil.toJson(searchRequest));

        BoxDeveloperEditionAPIConnection api = null;
        try {
            api = getBoxApiConnection();
//        api.asUser(appUserId);
        } catch (BoxAPIException e) {
            int responseCode = e.getResponseCode();
            if (responseCode == 404) {
                setupError("404", "Folder not found");
            }
            setupError("500", "Search error");
        }

        switch (searchRequest.getSearchType().toLowerCase()) {
            case "folder": {
                String folderId = searchRequest.getFolderId();
                try {
                    BoxFolder folder = new BoxFolder(api, folderId);
                    Metadata folderMetadata = folder.getMetadata(appProperties.getCitizenMetadataTemplate(), CITIZEN_METADATA_SCOPE);
                    logger.info(folderMetadata.toString());
                    List<FileInfo> files = new ArrayList<>();
                    PartialCollection<BoxItem.Info> items = folder.getChildrenRange(offset, limit);
                    for (BoxItem.Info itemInfo : items) {
                        FileInfo fileInfo = getItemInfo(itemInfo, folderMetadata);
                        files.add(fileInfo);
                    }
                    long allItemCount = items.fullSize();
                    boolean complete = (allItemCount > (offset+limit));
                    prepareSearchResult(exchange, files, complete);
                } catch (BoxAPIException e) {
                    int responseCode = e.getResponseCode();
                    if (responseCode == 404) {
                        setupError("404", "Folder not found");
                    }
                    setupError("500", "Search error");
                }
                break;
            }
            case "file": {
                String folderId = searchRequest.getFolderId();
                String fileName = searchRequest.getFileName();
                BoxFolder folder = new BoxFolder(api, folderId);
                Metadata folderMetadata = folder.getMetadata(appProperties.getCitizenMetadataTemplate(), CITIZEN_METADATA_SCOPE);
                limit++;
                long position = offset;
                long count = 0;
                List<FileInfo> files = new ArrayList<>();
                for (BoxItem.Info info : folder) {
                    if (info instanceof BoxFile.Info) {
                        String itemName = info.getName();
                        if (fileName.equals(itemName)) {
                            position++;
                            if (position >= offset) {
                                FileInfo fileInfo = getItemInfo(info, folderMetadata);
                                files.add(fileInfo);
                                count++;
                                if (count == limit) {
                                    break;
                                }
                            }
                        }
                    }
                }
                boolean complete = (count < limit);
                prepareSearchResult(exchange, files, complete);
                break;
            }
        }

    }

    private void prepareSearchResult(Exchange exchange, List<FileInfo> files, boolean complete) {
        SearchResult searchResult = new SearchResult();
        searchResult.setFileData(files);
        searchResult.setComplete(Boolean.toString(complete));
        logger.info("Search success response is {}", JsonUtil.toJson(searchResult));
        setupResponse(exchange, "200", searchResult, SearchResult.class);
    }

    private FileInfo getItemInfo(BoxItem.Info itemInfo, Metadata folderMetadata) {
        FileInfo fileInfo = new FileInfo();
        String fileId = itemInfo.getID();
        String name = itemInfo.getName();
        String itemType = itemInfo.getType();
        fileInfo.setFileId(fileId);
        fileInfo.setFileName(name);
        fileInfo.setItemType(itemType);
        CitizenMetadata citizenMetadata = getCitizenMetadata(folderMetadata);
        fileInfo.setCitizenMetadata(citizenMetadata);
        return fileInfo;
    }

    private CitizenMetadata getCitizenMetadata(Metadata folderMetadata) {
        CitizenMetadata citizenMetadata = new CitizenMetadata();
        citizenMetadata.setFirstName(getMetadataStringField(folderMetadata, "/FirstName"));
        citizenMetadata.setLastName(getMetadataStringField(folderMetadata, "/LastName"));
        citizenMetadata.setSsn4(getMetadataStringField(folderMetadata, "/Last4ofSSN"));
        citizenMetadata.setLogonUserId(getMetadataStringField(folderMetadata, "/logon_user_id"));
        citizenMetadata.setMpiId(getMetadataStringField(folderMetadata, "/mpi_id"));
        citizenMetadata.setSysId(getMetadataStringField(folderMetadata, "/sys_id"));
        citizenMetadata.setDob(getMetadataDateField(folderMetadata,"/dob"));
        return citizenMetadata;
    }

    private String getMetadataStringField(Metadata metadata, String fieldPath) {
        String fieldValue = null;
        try {
            fieldValue = metadata.getString(fieldPath);
        } catch (Exception ignored)
        {}
        return fieldValue;
    }

    private LocalDate getMetadataDateField(Metadata metadata, String fieldPath) {
        LocalDate fieldValue = null;
        try {
            String fieldValueAsString = metadata.getString(fieldPath);
            String dateValueAsString = fieldValueAsString.substring(0, fieldValueAsString.indexOf('T'));
            fieldValue = LocalDate.parse(dateValueAsString);
        } catch (Exception ignored)
        {}
        return fieldValue;
    }

}
