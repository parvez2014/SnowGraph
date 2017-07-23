package graphsearcher;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.neo4j.graphalgo.GraphAlgoFactory;
import org.neo4j.graphalgo.PathFinder;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.PathExpanders;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.ResourceIterable;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.tartarus.snowball.ext.EnglishStemmer;

import graphdb.extractors.miners.codeembedding.line.LINEExtracter;
import graphdb.extractors.parsers.javacode.JavaCodeExtractor;

public class GraphSearcher {

	GraphDatabaseService db = null;
	PathFinder<Path> pathFinder = GraphAlgoFactory
			.shortestPath(PathExpanders.forTypesAndDirections(RelationshipType.withName(JavaCodeExtractor.EXTEND),
					Direction.BOTH, RelationshipType.withName(JavaCodeExtractor.IMPLEMENT), Direction.BOTH,
					// RelationshipType.withName(JavaCodeExtractor.THROW),
					// Direction.BOTH,
					RelationshipType.withName(JavaCodeExtractor.PARAM), Direction.BOTH,
					RelationshipType.withName(JavaCodeExtractor.RT), Direction.BOTH,
					RelationshipType.withName(JavaCodeExtractor.HAVE_METHOD), Direction.BOTH,
					RelationshipType.withName(JavaCodeExtractor.HAVE_FIELD), Direction.BOTH,
					RelationshipType.withName(JavaCodeExtractor.CALL_METHOD), Direction.BOTH,
					RelationshipType.withName(JavaCodeExtractor.CALL_FIELD), Direction.BOTH,
					RelationshipType.withName(JavaCodeExtractor.TYPE), Direction.BOTH,
					RelationshipType.withName(JavaCodeExtractor.VARIABLE), Direction.BOTH), 5);

	boolean debug = true;

	static EnglishStemmer stemmer = new EnglishStemmer();
	static QueryStringToQueryWordsConverter converter = new QueryStringToQueryWordsConverter();

	Map<Long, List<Double>> id2Vec = new HashMap<>();
	Map<Long, String> id2Sig = new HashMap<>();
	Map<Long, String> id2Name = new HashMap<>();
	Map<Long, Set<String>> id2Words = new HashMap<>();
	Set<Long> typeSet = new HashSet<>();

	Map<String, Set<Long>> word2Ids = new HashMap<>();

	Map<String, Set<Long>> queryWord2Ids = new HashMap<>();
	Set<String> queryWordSet = new HashSet<>();

	public static void main(String[] args) {
		String testQuery = "获取所有人的邮件信息";
		String graphPath = "E:/SnowGraphData/dc/graphdb";
		GraphDatabaseService db = new GraphDatabaseFactory().newEmbeddedDatabase(new File(graphPath));
		GraphSearcher graphSearcher = new GraphSearcher(db);
		Set<Long> res = graphSearcher.computeAnchors(testQuery);

		for (long id : res)
			System.out.println(graphSearcher.id2Sig.get(id));

	}

	void testDist() {
		long node1 = 0;
		for (long id : typeSet)
			if (id2Name.get(id).equals("indexread"))
				node1 = id;
		for (long id : id2Name.keySet())
			if (id2Sig.get(id).contains("org.apache.lucene.index.IndexReader.")) {
				System.out.println(id2Sig.get(id) + " " + dist(node1, id));
			}
	}

	public GraphSearcher(GraphDatabaseService db) {
		this.db = db;
		try (Transaction tx = db.beginTx()) {
			ResourceIterable<Node> nodes = db.getAllNodes();
			for (Node node : nodes) {
				if (!node.hasLabel(Label.label(JavaCodeExtractor.CLASS))
						&& !node.hasLabel(Label.label(JavaCodeExtractor.INTERFACE))
						&& !node.hasLabel(Label.label(JavaCodeExtractor.METHOD)))
					continue;
				if (!node.hasProperty(LINEExtracter.LINE_VEC))
					continue;
				String[] eles = ((String) node.getProperty(LINEExtracter.LINE_VEC)).trim().split("\\s+");
				List<Double> vec = new ArrayList<Double>();
				for (String e : eles)
					vec.add(Double.parseDouble(e));
				long id = node.getId();
				String sig = (String) node.getProperty(JavaCodeExtractor.SIGNATURE);
				if (sig.toLowerCase().contains("test"))
					continue;
				String name = "";
				if (node.hasLabel(Label.label(JavaCodeExtractor.CLASS)))
					name = (String) node.getProperty(JavaCodeExtractor.CLASS_NAME);
				if (node.hasLabel(Label.label(JavaCodeExtractor.INTERFACE)))
					name = (String) node.getProperty(JavaCodeExtractor.INTERFACE_NAME);
				if (node.hasLabel(Label.label(JavaCodeExtractor.METHOD)))
					name = (String) node.getProperty(JavaCodeExtractor.METHOD_NAME);
				if (node.hasLabel(Label.label(JavaCodeExtractor.METHOD)) && name.matches("[A-Z]\\w+"))
					continue;
				Set<String> words = new HashSet<>();
				for (String e : name.split("[^A-Za-z]+"))
					for (String word : camelSplit(e)) {
						word = stem(word);
						if (!word2Ids.containsKey(word))
							word2Ids.put(word, new HashSet<>());
						word2Ids.get(word).add(id);
						words.add(word);
					}
				if (node.hasProperty(JavaCodeExtractor.CLASS_CHINESE_TOKENS)
						|| node.hasProperty(JavaCodeExtractor.INTERFACE_CHINESE_TOKENS)
						|| node.hasProperty(JavaCodeExtractor.METHOD_CHINESE_TOKENS)) {
					String cTokenString = node.hasProperty(JavaCodeExtractor.CLASS_CHINESE_TOKENS)
							? ((String) node.getProperty(JavaCodeExtractor.CLASS_CHINESE_TOKENS))
							: node.hasProperty(JavaCodeExtractor.METHOD_CHINESE_TOKENS)
									? ((String) node.getProperty(JavaCodeExtractor.METHOD_CHINESE_TOKENS))
									: ((String) node.getProperty(JavaCodeExtractor.INTERFACE_CHINESE_TOKENS));
					for (String word : cTokenString.trim().split("\\s+")) {
						if (!word2Ids.containsKey(word))
							word2Ids.put(word, new HashSet<>());
						word2Ids.get(word).add(id);
						words.add(word);
					}
				}
				id2Words.put(id, words);
				id2Vec.put(id, vec);
				id2Sig.put(id, sig);
				if (node.hasLabel(Label.label(JavaCodeExtractor.CLASS))
						|| node.hasLabel(Label.label(JavaCodeExtractor.INTERFACE)))
					typeSet.add(id);
				id2Name.put(id, stem(name.toLowerCase()));
			}
			tx.success();
		}
	}

	public SearchResult query(String queryString) {

		System.out.println(queryString);
		Set<Long> anchors = computeAnchors(queryString);
		SearchResult r = new SearchResult();
		r.nodes.addAll(anchors);
		try (Transaction tx = db.beginTx()) {
			for (long anchor1 : anchors)
				for (long anchor2 : anchors) {
					Path path = pathFinder.findSinglePath(db.getNodeById(anchor1), db.getNodeById(anchor2));
					if (path != null) {
						for (Node node : path.nodes())
							r.nodes.add(node.getId());
						for (Relationship edge : path.relationships())
							r.edges.add(edge.getId());
					}
				}
			Set<Long> tmpSet=new HashSet<>();
			tmpSet.addAll(r.nodes);
			for (long node : tmpSet) {
				Iterator<Relationship> classInter = db.getNodeById(node)
						.getRelationships(RelationshipType.withName(JavaCodeExtractor.HAVE_METHOD), Direction.INCOMING)
						.iterator();
				if (!classInter.hasNext())
					continue;
				Relationship edge = classInter.next();
				r.nodes.add(edge.getStartNodeId());
				r.edges.add(edge.getId());
			}
			tx.success();
		}
		return r;
	}

	Set<Long> computeAnchors(String queryString) {

		queryWord2Ids = new HashMap<>();
		queryWordSet = new HashSet<>();

		queryWordSet = converter.convert(queryString);
		if (debug) {
			System.out.println("queryWordSet = { " + String.join(", ", queryWordSet) + " }");
			System.out.println();
		}

		queryWord2Ids(queryWordSet);

		Set<Long> candidateNodes = candidate();

		double minCost = Double.MAX_VALUE;
		Set<Long> minCostNodeSet = new HashSet<>();
		for (long cNode : candidateNodes) {
			if (debug)
				System.out.println("cNode: " + id2Sig.get(cNode));
			Set<Long> minDistNodeSet = new HashSet<>();
			minDistNodeSet.add(cNode);
			for (String queryWord : queryWordSet) {
				if (queryWord2Ids.get(queryWord).contains(cNode))
					continue;
				double minDist = Double.MAX_VALUE;
				long minDistNode = -1;
				for (long node : queryWord2Ids.get(queryWord)) {
					double dist = dist(cNode, node);
					if (dist < minDist) {
						minDist = dist;
						minDistNode = node;
					}
				}
				if (minDistNode==-1)
					continue;
				minDistNodeSet.add(minDistNode);
				if (debug)
					System.out.println(queryWord + " " + id2Sig.get(minDistNode) + " " + minDist);
			}
			double cost = sumDist(cNode, minDistNodeSet);
			if (cost < minCost) {
				minCost = cost;
				minCostNodeSet = minDistNodeSet;
			}
			if (debug)
				System.out.println();
		}

		return minCostNodeSet;

	}

	Set<Long> candidate() {

		Set<Long> candidateNodes = new HashSet<>();

		for (long node : typeSet)
			if (queryWordSet.contains(id2Name.get(node)))
				candidateNodes.add(node);

		for (long node : id2Name.keySet()) {
			double count = 0;
			for (String word : id2Words.get(node))
				if (queryWordSet.contains(word))
					count++;
			if (count >= 2)
				candidateNodes.add(node);
		}

		if (candidateNodes.size() > 0)
			return candidateNodes;

		for (String queryWord : queryWordSet)
			for (long node : queryWord2Ids.get(queryWord)) {
				if (!typeSet.contains(node))
					continue;
				candidateNodes.add(node);
			}

		if (candidateNodes.size() > 0)
			return candidateNodes;

		for (String queryWord : queryWordSet)
			for (long node : queryWord2Ids.get(queryWord)) {
				candidateNodes.add(node);
			}

		return candidateNodes;
	}

	double sumDist(long cNode, Set<Long> minDistNodeSet) {
		double r = 0;
		for (long node : minDistNodeSet) {
			double dist = dist(cNode, node);
			r += dist;
		}
		return r;
	}

	void queryWord2Ids(Set<String> queryWordSet) {
		for (String queryWord : queryWordSet) {
			queryWord2Ids.put(queryWord, new HashSet<>());
			queryWord2Ids.put(queryWord, word2Ids.containsKey(queryWord) ? word2Ids.get(queryWord) : new HashSet<>());
			for (long node : id2Name.keySet())
				if (id2Name.get(node).equals(queryWord))
					queryWord2Ids.get(queryWord).add(node);
		}
		for (String word : queryWord2Ids.keySet())
			if (queryWord2Ids.get(word).size() > 0)
				queryWordSet.add(word);
	}

	String stem(String word) {
		if (word.matches("\\w+")) {
			stemmer.setCurrent(word);
			stemmer.stem();
			word = stemmer.getCurrent();
		}
		return word;
	}

	static List<String> camelSplit(String e) {
		List<String> r = new ArrayList<String>();
		Matcher m = Pattern.compile("^([a-z]+)|([A-Z][a-z]+)|([A-Z]+(?=([A-Z]|$)))").matcher(e);
		if (m.find()) {
			String s = m.group().toLowerCase();
			r.add(s);
			if (s.length() < e.length())
				r.addAll(camelSplit(e.substring(s.length())));
		}
		return r;
	}

	double dist(long node1, long node2) {
		double r = 0;
		for (int i = 0; i < id2Vec.get(node1).size(); i++)
			r += (id2Vec.get(node1).get(i) - id2Vec.get(node2).get(i))
					* (id2Vec.get(node1).get(i) - id2Vec.get(node2).get(i));
		r = Math.sqrt(r);
		return r;
	}

}
