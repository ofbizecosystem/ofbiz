package org.ofbiz.content.jcr.helper;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import javax.jcr.ItemExistsException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.jackrabbit.ocm.exception.ObjectContentManagerException;
import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.jcr.access.jackrabbit.ConstantsJackrabbit;
import org.ofbiz.jcr.access.jackrabbit.RepositoryAccessJackrabbit;
import org.ofbiz.jcr.orm.OfbizRepositoryMapping;
import org.ofbiz.jcr.orm.jackrabbit.OfbizRepositoryMappingJackrabbitArticle;

/**
 * This Helper class encapsulate the jcr article content bean. it provide all
 * attributes and operations which are necessary to work with the content
 * repository.
 *
 * The concrete implementations covers the different content use case related
 * workflows. I.E. Different behavior for File/Folder or Text content.
 *
 * The Helper classes should be build on top of the generic JCR implementation
 * in the Framework.
 *
 */
public class JcrArticleHelper {

    private final static String module = JcrArticleHelper.class.getName();

    private static RepositoryAccessJackrabbit access = null;
    private static OfbizRepositoryMappingJackrabbitArticle article = null;

    private static List<String> possibleLocales = null;

    static {
        if (UtilValidate.isEmpty(possibleLocales)) {
            possibleLocales = new ArrayList<String>();
            List<Locale> locales = org.ofbiz.base.util.UtilMisc.availableLocales();
            for (Locale locale : locales) {
                possibleLocales.add(locale.toString());
            }
        }
    }

    /**
     * Setup my content Object
     */
    public JcrArticleHelper(GenericValue userLogin) {
        access = new RepositoryAccessJackrabbit(userLogin);
    }

    /**
     * This will close the connection to the content repository and make sure
     * that all changes a stored successfully.
     */
    public void closeContentSession() {
        access.closeAccess();
        access = null;
    }

    /**
     * Read the article content object from the repository. Throws an Exception
     * when the read content type is not an article content type.
     *
     * @param contentPath
     * @return content article object
     */
    public OfbizRepositoryMappingJackrabbitArticle readContentFromRepository(String contentPath) {
        OfbizRepositoryMapping orm = access.getContentObject(contentPath);

        if (orm instanceof OfbizRepositoryMappingJackrabbitArticle) {
            article = (OfbizRepositoryMappingJackrabbitArticle) orm;
            article.setVersion(access.getBaseVersion(contentPath));
            return article;
        } else {
            throw new ClassCastException("The content object for the path: " + contentPath + " is not an article content object. This Helper can only handle content objects with the type: " + OfbizRepositoryMappingJackrabbitArticle.class.getName());
        }
    }

    /**
     * Read the article content object, in the passed language, from the
     * repository. if the language is not available, the default language will
     * be choose. Throws an Exception when the read content type is not an
     * article content type.
     *
     * @param contentPath
     * @return content article object
     */
    public OfbizRepositoryMappingJackrabbitArticle readContentFromRepository(String contentPath, String language) {
        contentPath = determineContentLanguagePath(contentPath, language);
        return readContentFromRepository(contentPath);
    }

    /**
     * Read the article content object, in the passed language and version, from
     * the repository. if the language is not available, the default language
     * will be choose. Throws an Exception when the read content type is not an
     * article content type.
     *
     * @param contentPath
     * @param language
     * @param version
     * @return
     */
    public OfbizRepositoryMappingJackrabbitArticle readContentFromRepository(String contentPath, String language, String version) {
        contentPath = determineContentLanguagePath(contentPath, language);
        OfbizRepositoryMapping orm = access.getContentObject(contentPath, version);

        if (orm instanceof OfbizRepositoryMappingJackrabbitArticle) {
            article = (OfbizRepositoryMappingJackrabbitArticle) orm;
            article.setVersion(version);
            return article;
        } else {
            throw new ClassCastException("The content object for the path: " + contentPath + " is not an article content object. This Helper can only handle content objects with the type: " + OfbizRepositoryMappingJackrabbitArticle.class.getName());
        }
    }

    /**
     * Stores a new article content object in the repository.
     *
     * @param contentPath
     * @param language
     * @param title
     * @param content
     * @param pubDate
     * @throws ObjectContentManagerException
     * @throws ItemExistsException
     */
    public void storeContentInRepository(String contentPath, String language, String title, String content, Calendar pubDate) throws ObjectContentManagerException, ItemExistsException {
        if (UtilValidate.isEmpty(language)) {
            language = determindeTheDefaultLanguage();
        }

        // the content path should contain the language information
        // TODO this have to be a little bit more intelligent in the future
        if (!contentPath.endsWith(language)) {
            if (contentPath.endsWith("/")) {
                contentPath = contentPath + language;
            } else {
                contentPath = contentPath + "/" + language;
            }
        }

        // construct the content article object
        article = new OfbizRepositoryMappingJackrabbitArticle(contentPath, language, title, content, pubDate);

        access.storeContentObject(article);

    }

    /**
     * Returns a list of versions which are available for the current article.
     * If no article is loaded before, the list will be empty.
     *
     * @return
     */
    public List<String> getVersionListForCurrentArticle() {
        List<String> versions = null;

        if (article != null) {
            versions = access.getVersionList(article.getPath());
        } else {
            Debug.logWarning("No Article is loaded from the repository, please load an article first before requesting the version list.", module);
            versions = new ArrayList<String>();
        }

        return versions;
    }

    /**
     * This method should determine the correct language for the content. It
     * covers the case when the passed language is not available.
     *
     * A default (system) language will be taken, if the passed language, does
     * not exist, if no default language node is specified the first language
     * node which will be found will be choose.
     *
     * @param contentPath
     * @param language
     * @return
     */
    private String determineContentLanguagePath(String contentPath, String language) {
        // return if only the root node path is requested
        if (ConstantsJackrabbit.ROOTPATH.equals(contentPath)) {
            return contentPath;
        }

        // we have to check if the content path already contains a language and
        // if the language is already in the path we have to check if it is
        // equal to the passed language

        // we split the path string in chunks
        String[] path = contentPath.split("/");

        // chunk if the last chunk contains a language flag
        StringBuffer canonicalizedContentPath = new StringBuffer("/");
        if (possibleLocales.contains(path[path.length - 1])) {
            for (int i = 0; i < path.length - 1; i++) {
                if (UtilValidate.isNotEmpty(path[i])) {
                    canonicalizedContentPath.append(path[i]).append("/");
                }
            }
        } else {
            for (String p : path) {
                if (UtilValidate.isNotEmpty(p)) {
                    canonicalizedContentPath.append(p).append("/");
                }
            }
        }

        // check if this language exist in the repository
        Session session = access.getSession();
        try {
            if (!session.itemExists(canonicalizedContentPath.toString() + language)) {
                // check for default language
                if (!session.itemExists(canonicalizedContentPath.toString() + determindeTheDefaultLanguage())) {
                    // return the first available language
                    NodeIterator ni = session.getNode(canonicalizedContentPath.toString()).getNodes();
                    while (ni.hasNext()) {
                        Node n = ni.nextNode();
                        if (possibleLocales.contains(n.getName())) {
                            contentPath = n.getPath();
                            break;
                        }
                    }
                    ni = null;
                } else {
                    contentPath = canonicalizedContentPath.toString() + determindeTheDefaultLanguage();
                }
            } else {
                contentPath = canonicalizedContentPath.toString() + language;
            }
        } catch (RepositoryException e) {
            Debug.logError(e, module);
        }

        return contentPath;
    }

    private String determindeTheDefaultLanguage() {
        return UtilProperties.getPropertyValue("general", "locale.properties.fallback");
    }
}