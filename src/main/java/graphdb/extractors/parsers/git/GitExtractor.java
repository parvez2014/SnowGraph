package graphdb.extractors.parsers.git;

import graphdb.extractors.linkers.codeindoc.CodeIndexes;
import graphdb.extractors.parsers.git.entity.GitCommit;
import graphdb.extractors.parsers.git.entity.GitCommitAuthor;
import graphdb.extractors.parsers.git.entity.MutatedFile;
import graphdb.framework.Extractor;
import graphdb.framework.annotations.EntityDeclaration;
import graphdb.framework.annotations.PropertyDeclaration;

import graphdb.framework.annotations.RelationshipDeclaration;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;

import java.io.File;
import java.util.*;

/**
 * Created by oliver on 2017/5/22.
 */
public class GitExtractor implements Extractor {

    @EntityDeclaration
    public static final String COMMIT = "gitCommit";
    @PropertyDeclaration
    public static final String COMMIT_UUID = "UUID";
    @PropertyDeclaration
    public static final String COMMIT_VERSION = "version";
    @PropertyDeclaration
    public static final String COMMIT_AUTHOR = "author";
    @PropertyDeclaration
    public static final String COMMIT_DATE = "createDate";
    @PropertyDeclaration
    public static final String COMMIT_LOGMESSAGE = "logMessage";
    @PropertyDeclaration
    public static final String COMMIT_PARENT_UUID = "parentUUID";
    @PropertyDeclaration
    public static final String COMMIT_SVN_URL = "svnUrl";


    @EntityDeclaration
    public static final String COMMITAUTHOR = "gitCommitAuthor";
    @PropertyDeclaration
    public static final String COMMITAUTHOR_NAME = "name";


    @EntityDeclaration
    public static final String MUTATEDFILE = "mutatedFile";
    @PropertyDeclaration
    public static final String MUTATEDFILE_TYPE = "type";
    @PropertyDeclaration
    public static final String MUTATEDFILE_FILE_NAME = "fileName";
    @PropertyDeclaration
    public static final String MUTATEDFILE_API_QUALIFIEDNAME = "apiQualifiedName";
    @PropertyDeclaration
    public static final String MUTATEDFILE_API_NAME = "apiName";
    @PropertyDeclaration
    public static final String MUTATEDFILE_FORMER_NAME = "formerName";
    @PropertyDeclaration
    public static final String MUTATEDFILE_LATTER_NAME = "latterName";
    @PropertyDeclaration
    public static final String MUTATEDFILE_CREATER_UUID = "createrUUID";
    @PropertyDeclaration
    public static final String MUTATEDFILE_DELETER_UUID = "deleterUUID";

    @RelationshipDeclaration
    public static final String IS_AUTHOR_OF_COMMIT = "person_is_author_of_commit";
    @RelationshipDeclaration
    public static final String DELETER_OF_FILE = "deleter_of_file";
    @RelationshipDeclaration
    public static final String CREATER_OF_FILE = "creater_of_file";
    @RelationshipDeclaration
    public static final String MODIFIER_OF_FILE = "modifier_of_file";
    @RelationshipDeclaration
    public static final String PARENT_OF_COMMIT = "parent_of_commit";
    @RelationshipDeclaration
    public static final String COMMIT_CHANGE_THE_CLASS ="commit_change_the_class" ;
    @RelationshipDeclaration
    public static final String FILE_CONTAIN_THE_CLASS = "file_contain_the_class";


    GraphDatabaseService db = null;

    String gitFolderPath = null;


    private Map<String , Node> commitNodeMap = new HashMap<String , Node>();
    private Map<String , Node> authorNodeMap = new HashMap<String , Node>();
    private Map<String , Node> fileNodeMap = new HashMap<String , Node>();

    public void setGitFolderPath(String path){
        this.gitFolderPath = path;
    }

    public void build(File gitFolder , Map<String , Node> APIs){
        for(File gitFile : gitFolder.listFiles()){
            //gitFile = new File("I:\\lucene-solr\\commit0014931b1c7e10ac7fcd7060245f59c939b75cdf");
            if(gitFile.isFile() ){
                String postFix = gitFile.getName();
                if(postFix.lastIndexOf(".txt") == postFix.length() - 4)
                    continue;
                //System.out.println(gitFile.getAbsolutePath());
                try(Transaction tx = db.beginTx()) {
                    //region<create node for a commit>
                    Node commitNode = db.createNode();
                    //System.out.println("insert node");
                    GitCommit commit = new GitCommit(gitFile);
                    if(!commit.getStatus())
                        continue;
                    commit.createCommitNode(commitNode);
                    commitNodeMap.put(commit.getUUID(), commitNode);

                    //endregion

                    //region<create node for the author and build up the relationship between commit and author>
                    GitCommitAuthor author = commit.getAuthor();
                    if(author != null) {
                        String authorName = author.getAuthorName();
                        Node authorNode = null;
                        if (authorNodeMap.containsKey(authorName)) {
                            authorNode = authorNodeMap.get(authorName);
                        } else {
                            authorNode = db.createNode();
                            commit.getAuthor().createAuthorNode(authorNode);
                            authorNodeMap.put(authorName , authorNode);
                        }
                        GitCommitAuthor.createRelationshipTo(authorNode, commitNode, GitExtractor.IS_AUTHOR_OF_COMMIT);
                    }
                    //endregion

                    //region <build the relationship between commit and mutated file>
                    List<MutatedFile> files = commit.getMutatedFiles();
                    if(files != null) {

                        for (MutatedFile file : files) {
                            Node fileNode = null;
                            //region<build the relation between commit and mutated file>
                            try {
                                String fileName = file.getAbsolutePath();
                                if(fileName.length() > 0) {
                                    if (fileNodeMap.containsKey(fileName)) {
                                        fileNode = fileNodeMap.get(fileName);
                                    } else {
                                        fileNode = db.createNode();
                                        file.createMutatedFileNode(fileNode);
                                        fileNodeMap.put(fileName, fileNode);
                                    }

                                    MutatedFile.MutatedType type = file.getMutatedType();
                                    switch (type) {
                                        case ADDED: {
                                            MutatedFile.createrRelationshipTo(fileNode, commitNode, GitExtractor.CREATER_OF_FILE);
                                            break;
                                        }
                                        case DELETED: {
                                            MutatedFile.createrRelationshipTo(fileNode, commitNode, GitExtractor.DELETER_OF_FILE);
                                            break;
                                        }
                                        case MODIFIED: {
                                            MutatedFile.createrRelationshipTo(fileNode, commitNode, GitExtractor.MODIFIER_OF_FILE);
                                            break;
                                        }
                                    }
                                }

                            } catch (Exception e) {
                                System.out.println("when bulid a mutated file node fail.");
                                System.out.println("the commit uuid is :" + commit.getUUID());
                            }
                            //endregion<build the relation between commit and mutated file>

                            String apiQualifiedName = file.getApiQualifiedName();
                            if(apiQualifiedName.length() > 0){
                                Node apiNode = APIs.get(apiQualifiedName);
                                if(apiNode != null){
                                    GitCommit.createRelationshipTo(commitNode , apiNode , GitExtractor.COMMIT_CHANGE_THE_CLASS);
                                    MutatedFile.createrRelationshipTo(fileNode , apiNode , GitExtractor.FILE_CONTAIN_THE_CLASS );
                                }
                            }
                        }

                    }

                    //endregion
                    tx.success();
                }
            }else if(gitFile.isDirectory()){
                build(gitFile , APIs);
            }
        }
    }


    public void run(GraphDatabaseService db) {
        this.db = db;

        Map<String , Node> APIs = getNodes("Class");

        for(String key : commitNodeMap.keySet()){
            Node commit = commitNodeMap.get(key);
        }

        File gitFolder = new File(gitFolderPath);
        build(gitFolder , APIs);
        try(Transaction tx = db.beginTx()) {
            Node childNode;
            String[] parentUUID;
            String UUIDs;
            for (String key : commitNodeMap.keySet()) {
                childNode = commitNodeMap.get(key);
                UUIDs = (String) childNode.getProperty(GitExtractor.COMMIT_PARENT_UUID);
                if (UUIDs.trim().length() != 0) {
                    parentUUID = UUIDs.split(" ");
                    if(parentUUID.length > 1){
                        System.out.println("parent size > 2 : " + key + " , " + UUIDs);
                    }
                    for (int i = 0; i < parentUUID.length; i++) {
                        Node parentNode = commitNodeMap.get(parentUUID[i]);
                        if (parentNode != null) {
                            GitCommit.createRelationshipTo(parentNode, childNode, GitExtractor.PARENT_OF_COMMIT);
                        } else {
                            System.out.println(key + " has no parent : " + parentUUID);
                        }
                    }
                }
            }
            tx.success();
        }


    }

    private Map<String , Node> getNodes(String nodeType){
        Map<String , Node> result = new HashMap<String , Node>();
        switch (nodeType){
            case "Class":
            case "Interface":{
                CodeIndexes codeIndexs = new CodeIndexes(this.db);
                for(String key : codeIndexs.typeMap.keySet()){
                    try(Transaction tx = db.beginTx()){
                        Node node = db.getNodeById(codeIndexs.typeMap.get(key));
                        result.put(key , node);
                        tx.success();
                    }

                }
                break;
            }
        }
        return result;
    }
}