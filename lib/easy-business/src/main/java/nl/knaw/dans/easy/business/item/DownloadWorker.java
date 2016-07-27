package nl.knaw.dans.easy.business.item;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URL;
import java.util.*;

import nl.knaw.dans.common.lang.RepositoryException;
import nl.knaw.dans.common.lang.file.ZipItem;
import nl.knaw.dans.common.lang.file.ZipUtil;
import nl.knaw.dans.common.lang.repo.DmoStoreId;
import nl.knaw.dans.common.lang.repo.DsUnitId;
import nl.knaw.dans.common.lang.repo.UnitMetadata;
import nl.knaw.dans.common.lang.service.exceptions.CommonSecurityException;
import nl.knaw.dans.common.lang.service.exceptions.FileSizeException;
import nl.knaw.dans.common.lang.service.exceptions.ServiceException;
import nl.knaw.dans.common.lang.service.exceptions.TooManyFilesException;
import nl.knaw.dans.common.lang.service.exceptions.ZipFileLengthException;
import nl.knaw.dans.easy.data.Data;
import nl.knaw.dans.easy.data.store.FileStoreAccess;
import nl.knaw.dans.easy.data.store.StoreAccessException;
import nl.knaw.dans.easy.domain.dataset.AdditionalLicenseUnit;
import nl.knaw.dans.easy.domain.dataset.item.FileItemVO;
import nl.knaw.dans.easy.domain.dataset.item.FolderItemVO;
import nl.knaw.dans.easy.domain.dataset.item.ItemVO;
import nl.knaw.dans.easy.domain.dataset.item.RequestedItem;
import nl.knaw.dans.easy.domain.download.FileContentWrapper;
import nl.knaw.dans.easy.domain.download.ZipFileContentWrapper;
import nl.knaw.dans.easy.domain.exceptions.DomainException;
import nl.knaw.dans.easy.domain.model.Dataset;
import nl.knaw.dans.easy.domain.model.user.EasyUser;
import nl.knaw.dans.easy.servicelayer.DownloadFilter;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DownloadWorker {

    public static final int MEGA_BYTE = 1024 * 1024;
    public static final int MAX_DOWNLOAD_SIZE = Data.getDownloadLimit() * MEGA_BYTE;
    public static final int MAX_NUMBER_OF_FILES = Data.getMaxNumberOfFiles();
    public static final File ZIP_FILE_DIR = Data.getZipFileDir();

    private static final FileStoreAccess FILE_STORE_ACCESS = Data.getFileStoreAccess();
    private static final String METADATA_PATH = "meta/";
    static final String DESCRIPTIVE_METADATA_FILE_NAME = "file_metadata.xml";

    // TODO eliminate duplicate file for the wicket link, note that we needed to switch of filtering
    // resources in the pom file
    static final String GENERAL_CONDITIONS_FILE_NAME = "general_conditions_DANS.pdf";

    private static final int MAX_FILENAME_LENGTH = 25;

    private static final Logger logger = LoggerFactory.getLogger(DownloadWorker.class);

    protected DownloadWorker() {

    }

    protected FileContentWrapper getFileContent(final EasyUser sessionUser, final Dataset dataset, final DmoStoreId fileItemId) throws CommonSecurityException,
            ServiceException
    {
        final FileContentWrapper fileContentWrapper = new FileContentWrapper(fileItemId.getStoreId());
        final DownloadFilter downloadFilter = new DownloadFilter(sessionUser, dataset, FILE_STORE_ACCESS);
        try {
            final List<FileItemVO> itemList = FILE_STORE_ACCESS.findFilesById(Collections.singletonList(fileItemId));
            final List<? extends ItemVO> filteredItems = downloadFilter.apply(itemList);
            if (!filteredItems.isEmpty()) {
                final FileItemVO fileItemVO = (FileItemVO) filteredItems.get(0);
                if (fileItemVO.getSize() > MAX_DOWNLOAD_SIZE) {
                    // TODO make special exception
                    throw new FileSizeException(fileItemVO.getSize() / MEGA_BYTE, MAX_DOWNLOAD_SIZE / MEGA_BYTE);
                }

                fileContentWrapper.setFileItemVO(fileItemVO);
                final URL url = Data.getEasyStore().getFileURL(new DmoStoreId(fileItemVO.getSid()));
                fileContentWrapper.setURL(url);
            } else {
                throw new CommonSecurityException("Insufficient rights");
            }
        }
        catch (final DomainException e) {
            logger.error("Unable to apply download filter: ", e);
            throw new ServiceException(e);
        }
        catch (final StoreAccessException e) {
            logger.error("Unable to get file content: ", e);
            throw new ServiceException(e);
        }

        return fileContentWrapper;
    }

    protected ZipFileContentWrapper getZippedContent(final EasyUser sessionUser, final Dataset dataset, final Collection<RequestedItem> requestedItems)
            throws ServiceException
    {
        final ZipFileContentWrapper zippedContent = new ZipFileContentWrapper();
        final String baseFolderName = getBaseFoldername(dataset);
        try {
            final List<ItemVO> requesteItemVOs = getRequestedItemVOs(requestedItems);
            final DownloadFilter downloadFilter = new DownloadFilter(sessionUser, dataset, FILE_STORE_ACCESS);
            final List<? extends ItemVO> permittedItemVOs = downloadFilter.apply(requesteItemVOs);
            zippedContent.setDownloadedItemVOs(permittedItemVOs); // for downloadHistory
            final File zipFile = createZipFile(permittedItemVOs, getAdditionalLicenseUrl(dataset));
            zippedContent.setZipFile(zipFile);
            zippedContent.setFilename(System.currentTimeMillis() + "-" + baseFolderName + ".zip");
        }
        catch (final StoreAccessException e) {
            throw new ServiceException(e);
        }
        catch (final IOException e) {
            logger.error("Unable to create zip file: ", e);
            throw new ServiceException(e);
        }
        catch (final DomainException e) {
            logger.error("Unable to apply download filter: ", e);
            throw new ServiceException(e);
        }
        catch (RepositoryException e) {
            logger.error("Unable to create zip file: ", e);
            throw new ServiceException(e);
        }

        return zippedContent;
    }

    private URL getAdditionalLicenseUrl(final Dataset dataset) throws ServiceException, RepositoryException {
        List<UnitMetadata> addLicenseList = Data.getEasyStore().getUnitMetadata(dataset.getDmoStoreId(), new DsUnitId(AdditionalLicenseUnit.UNIT_ID));
        if (addLicenseList.isEmpty()) {
            return null;
        } else {
            return Data.getEasyStore().getFileURL(dataset.getDmoStoreId(), new DsUnitId(AdditionalLicenseUnit.UNIT_ID), new DateTime());
        }
    }

    protected File createZipFile(final List<? extends ItemVO> items, final URL additionalLicenseUrl) throws IOException, ZipFileLengthException,
            RepositoryException, TooManyFilesException
    {
        final List<ZipItem> zipItems = toZipItems(items);

        final URL generalConditionsUrl = DownloadWorker.class.getResource(GENERAL_CONDITIONS_FILE_NAME);
        if (generalConditionsUrl != null) {
            zipItems.add(new ZipItem(METADATA_PATH + GENERAL_CONDITIONS_FILE_NAME, generalConditionsUrl));
        } else {
            logger.error("\n!\n!\n!\n!\n!No " + GENERAL_CONDITIONS_FILE_NAME + " found!\n!\n!\n!\n!\n!");
        }

        if (additionalLicenseUrl != null)
            zipItems.add(new ZipItem(METADATA_PATH + AdditionalLicenseUnit.UNIT_LABEL, additionalLicenseUrl));

        final File descriptiveFileMetadata = createDescriptiveFileMetadataFile(items);
        if (descriptiveFileMetadata != null)
            zipItems.add(new ZipItem(METADATA_PATH + DESCRIPTIVE_METADATA_FILE_NAME, descriptiveFileMetadata.toURI().toURL()));

        final File checksumsFile = createSha1ChecksumsFile(items);
        zipItems.add(new ZipItem("manifest-sha1.txt", checksumsFile));

        final File zipFile = File.createTempFile("easy", ".zip", ZIP_FILE_DIR);
        ZipUtil.zipFiles(zipFile, zipItems);
        return zipFile;
    }

    private File createSha1ChecksumsFile(final List<? extends ItemVO> items) throws IOException {
        final File file = File.createTempFile("manifest-sha1", ".txt");
        PrintStream fileOutputStream = null;
        try {
            fileOutputStream = new PrintStream(file);
            for (final ItemVO item : items) {
                if (item instanceof FileItemVO) {
                    String path = item.getPath().trim();
                    String sha1 = ((FileItemVO) item).getSha1Checksum();
                    if (StringUtils.isBlank(sha1)) {
                        sha1 = "-------------not-calculated-------------";
                    }
                    fileOutputStream.println(sha1 + " " + path);
                }
            }
            fileOutputStream.close();
        }
        catch (FileNotFoundException e) {
            throw new IOException("Could not print checksums to file", e);
        }
        finally {
            IOUtils.closeQuietly(fileOutputStream);
        }
        return file;
    }

    // Note: could determine total size of files before trying to zip them
    private List<ZipItem> toZipItems(final List<? extends ItemVO> items) throws ZipFileLengthException, TooManyFilesException {
        if (items.size() > MAX_NUMBER_OF_FILES) {
            throw new TooManyFilesException(items.size(), MAX_NUMBER_OF_FILES);
        }
        final List<ZipItem> zipItems = new ArrayList<ZipItem>();

        long totalSize = calculateTotalSizeUnzipped(items);
        logger.debug("total size unzipped " + totalSize);

        if (totalSize > MAX_DOWNLOAD_SIZE) {
            throw new ZipFileLengthException(totalSize / MEGA_BYTE, MAX_DOWNLOAD_SIZE / MEGA_BYTE);
        } else {
            // add zip items
            for (final ItemVO item : items) {
                if (item instanceof FileItemVO) {
                    totalSize += ((FileItemVO) item).getSize();
                    final URL url = Data.getEasyStore().getFileURL(new DmoStoreId(item.getSid()));
                    final ZipItem zipFileItem = new ZipItem(item.getPath(), url);
                    zipItems.add(zipFileItem);
                } else if (item instanceof FolderItemVO) {
                    final ZipItem zipFolderItem = new ZipItem(item.getPath());
                    zipItems.add(zipFolderItem);
                } else {
                    logger.warn("Unknown Item type: " + item);
                }
            }
        }

        return zipItems;
    }

    private long calculateTotalSizeUnzipped(final List<? extends ItemVO> items) {
        long totalSize = 0;
        for (final ItemVO item : items) {
            if (item instanceof FileItemVO) {
                totalSize += ((FileItemVO) item).getSize();
            }
        }
        return totalSize;
    }

    /**
     * Creates an XML file with descriptive metadata of downloaded files.
     * 
     * @param items
     *        files selected by the user that have download permission
     * @return a temporary file containing the metatdata
     * @throws IOException
     * @throws RepositoryException
     */
    File createDescriptiveFileMetadataFile(final List<? extends ItemVO> items) throws IOException, RepositoryException {
        final File metaFile = File.createTempFile("meta", ".xml");
        boolean hasFileItems = false;
        final PrintStream metaOutputStream = new PrintStream(metaFile);
        metaOutputStream.println("<?xml version='1.0' encoding='UTF-8'?>");
        metaOutputStream.println("<metadata>");

        for (final ItemVO item : items) {
            if (item instanceof FileItemVO) {
                collectMetadata(metaOutputStream, item);
                hasFileItems = true;
            }
        }
        metaOutputStream.println("</metadata>");
        metaOutputStream.close();
        if (hasFileItems) {
            return metaFile;
        }
        return null;
    }

    private void collectMetadata(final PrintStream metaOutputStream, final ItemVO item) throws IOException, RepositoryException {
        InputStream stream = Data.getEasyStore().getDescriptiveMetadataURL(new DmoStoreId(item.getSid())).openStream();
        int b;
        while (0 < (b = stream.read())) {
            metaOutputStream.write(b);
        }
        stream.close();
    }

    private static List<ItemVO> getRequestedItemVOs(final Collection<RequestedItem> requestedItems) throws StoreAccessException {
        // TODO move fedoraFileStoreAccess: saves transactions
        // TODO merge with FileMetadataPanel.getFileItemsRecursively and
        // FileExplorerUpdateCommand.createSidList
        final List<ItemVO> itemVOs = new ArrayList<ItemVO>();
        final List<DmoStoreId> leaves = new ArrayList<DmoStoreId>();
        for (final RequestedItem requestItem : requestedItems) {
            if (requestItem.isFile()) {
                leaves.add(new DmoStoreId(requestItem.getStoreId()));
            } else if (requestItem.filesOnly()) {
                itemVOs.addAll(FILE_STORE_ACCESS.getFiles(new DmoStoreId(requestItem.getStoreId())));

            } else {
                recursiveGet(itemVOs, new DmoStoreId(requestItem.getStoreId()));
            }
        }
        if (!leaves.isEmpty()) {
            itemVOs.addAll(FILE_STORE_ACCESS.findFilesById(leaves));
        }
        return itemVOs;
    }

    private static void recursiveGet(final List<ItemVO> itemVOs, final DmoStoreId folderId) throws StoreAccessException {
        final List<ItemVO> children = FILE_STORE_ACCESS.getFilesAndFolders(folderId);
        for (final ItemVO itemVO : children) {
            if (itemVO instanceof FolderItemVO) {
                recursiveGet(itemVOs, new DmoStoreId(itemVO.getSid()));
            }
        }
        itemVOs.addAll(children);
    }

    public static String getBaseFoldername(final Dataset dataset) {
        String title = dataset.getPreferredTitle();
        if (title.length() > MAX_FILENAME_LENGTH) {
            title = title.substring(0, MAX_FILENAME_LENGTH);
        }
        return title.replaceAll(" ", "_");
    }

}
