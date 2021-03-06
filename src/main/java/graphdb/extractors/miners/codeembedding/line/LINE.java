package graphdb.extractors.miners.codeembedding.line;

import org.neo4j.graphdb.*;

import graphdb.extractors.parsers.javacode.JavaCodeExtractor;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;

/**
 * Created by laurence on 17-7-15.
 */
public class LINE {
    static final int neg_table_size = (int)1e8;
    static final int sigmoid_table_size = 1000;
    static final int SIGMOID_BOUND = 6;
    static final double NEG_SAMPLING_POWER = 0.75;

    int num_threads = 1, order = 1, dim = 200, num_negative = 5;
    double init_rho = 0.025, rho;
    int  num_vertices = 0, num_edges = 0;
    int total_samples = 100, current_sample_count = 0;

    List<Edge> edges = new ArrayList<>();
    Map<Long, Vertex> vertex = new HashMap<>();
    long[] alias, neg_table;
    double[] prob, sigmoid_table;
    Random randomGenerator;

    public void readData(GraphDatabaseService db){
        try (Transaction tx = db.beginTx()) {
            ResourceIterator<Relationship> relIter = db.getAllRelationships().iterator();
            while (relIter.hasNext()) {
                Relationship relation = relIter.next();
                if (!JavaCodeExtractor.isJavaCodeRelationship(relation))
                    continue;
                Node start = relation.getStartNode();
                Node end = relation.getEndNode();
                long src = start.getId();
                long tgt = end.getId();
                edges.add(new Edge(src, tgt, 1));
                edges.add(new Edge(tgt, src, 1));
                Vertex cur = new Vertex(2.0);
                Vertex oldv = vertex.put(src, cur);
                if (oldv != null){
                    cur.degree += oldv.degree;
                }
                cur = new Vertex(1.0);
                oldv = vertex.put(tgt, cur);
                if (oldv != null){
                    cur.degree += oldv.degree;
                }

            }
            tx.success();
            num_edges = edges.size();
            num_vertices = vertex.size();
            System.out.println("num of veticies: " + num_vertices + '\n');
            System.out.println("num of edges: " + num_edges + '\n');
        }
    }
    public void initAliasTable() {
        alias = new long[num_edges];
        prob = new double[num_edges];
        double[] norm_prob = new double[num_edges];
        int[] large_block = new int[num_edges];
        int[] small_block = new int[num_edges];
        double sum = 0;
        int cur_small_block, cur_large_block;
        int num_small_block = 0, num_large_block = 0;
        for (int k = 0; k != num_edges; ++k)
            sum += edges.get(k).weight;
        for (int k = 0; k != num_edges; ++k)
            norm_prob[k] = edges.get(k).weight * num_edges / sum;
        for (int k = num_edges - 1; k >= 0; k--){
            if (norm_prob[k] < 1)
                small_block[num_small_block++] = k;
            else
                large_block[num_large_block++] = k;
        }
        while(num_small_block > 0 && num_large_block > 0){
            cur_small_block = small_block[--num_small_block];
            cur_large_block = large_block[--num_large_block];
            prob[cur_small_block] = norm_prob[cur_small_block];
            alias[cur_small_block] = cur_large_block;
            norm_prob[cur_large_block] = norm_prob[cur_large_block];
            if (norm_prob[cur_large_block] < 1)
                small_block[num_small_block++] = cur_large_block;
            else
                large_block[num_large_block++] = cur_large_block;
        }
        while(num_large_block > 0)
            prob[large_block[--num_large_block]] = 1;
        while(num_small_block > 0)
            prob[small_block[--num_small_block]] = 1;
    }
    public void initVector(){
        for (long key : vertex.keySet()){
            Vertex v = vertex.get(key);
            v.emb_context = new double[dim];
            v.emb_vertex = new double[dim];
            for (int i = 0; i < dim; ++i)
                v.emb_vertex[i] = (Math.random() - 0.5) / dim;
        }
    }
    public void initNegTable(){
        double sum = 0, cur_sum = 0, por = 0;
        Iterator iter = vertex.entrySet().iterator();
        long vid = 0;
        neg_table = new long[neg_table_size];
        for (long key: vertex.keySet()){
            Vertex v = vertex.get(key);
            sum += Math.pow(v.degree, NEG_SAMPLING_POWER);
        }
        for (int k = 0; k != neg_table_size; ++k){
            if ((double)(k + 1) / neg_table_size > por){
                Map.Entry entry = (Map.Entry)iter.next();
                Vertex v = (Vertex)entry.getValue();
                cur_sum += Math.pow(v.degree, NEG_SAMPLING_POWER);
                por = cur_sum / sum;
                vid = (long)entry.getKey();
            }
            neg_table[k] = vid;
        }
    }

    public void initSigmoidTable(){
        double x;
        sigmoid_table = new double[sigmoid_table_size + 1];
        for (int k = 0; k != sigmoid_table_size; ++k){
            x = 2 * SIGMOID_BOUND * k / sigmoid_table_size - SIGMOID_BOUND;
            sigmoid_table[k] = 1 / (1 + Math.exp(-x));
        }
    }

    public double fastSigmoid(double x){
        if (x > SIGMOID_BOUND) return 1;
        else if (x < -SIGMOID_BOUND) return 0;
        int k = (int)((x + SIGMOID_BOUND) * sigmoid_table_size / SIGMOID_BOUND / 2);
        return sigmoid_table[k];
    }

    public long sampleAnEdge(double rand_value1, double rand_value2){
        int k = (int)(num_edges * rand_value1);
        return rand_value2 < prob[k] ? k : alias[k];
    }

    public void update(double[] vec_u, double[] vec_v, double[] vec_error, long label){
        double x = 0, g;
        for (int c = 0; c != dim; ++c)
            x += vec_u[c] * vec_v[c];
        g = (label - fastSigmoid(x)) * rho;
        for (int c = 0; c != dim; ++c)
            vec_error[c] += g * vec_v[c];
        for (int c = 0; c != dim; ++c)
            vec_v[c] += g * vec_u[c];
    }

    public void trainLINE(){
        long u, v, target, label;
        long count = 0, last_count = 0;
        int curedge;

        long seed = 1L;
        double[] vec_error = new double[dim];
        while(true){
            if (count > total_samples / num_threads + 2) break;
            if (count - last_count > 10000){
                current_sample_count += count - last_count;
                last_count = count;
                //System.out.println("Rho: "+rho+"Progress: "+(double)current_sample_count/(double)(total_samples+1)*100+'%');
                rho = init_rho * (1 - current_sample_count / (double)(total_samples + 1));
                if (rho < init_rho * 0.0001)
                    rho = init_rho * 0.0001;
            }
            curedge = (int)sampleAnEdge(randomGenerator.nextDouble(), randomGenerator.nextDouble());
            u = edges.get(curedge).src;
            v = edges.get(curedge).tgt;
            for (int c = 0; c != dim; ++c)
                vec_error[c] = 0;
            double[] emb = vertex.get(u).emb_vertex;

            for (int d = 0; d != num_negative + 1; ++d){
                if (d == 0){
                    target = v;
                    label = 1;
                } else{
                    seed = seed * 25214903917L + 11;
                    int idx = (int)((seed >>> 16)  % neg_table_size);
                    target = neg_table[idx];
                    label = 0;
                }
                if (order == 1)
                    update(emb, vertex.get(target).emb_vertex, vec_error, label);
                if (order == 2)
                    update(emb, vertex.get(target).emb_context, vec_error, label);
            }

            for (int c = 0; c != dim; ++c)
                emb[c] += vec_error[c];
            count++;
        }
    }

    public void writeToTxt() {
        try {
            PrintWriter writer = new PrintWriter(new FileOutputStream("/home/laurence/Desktop/graph_embedding"));
            writer.write(vertex.size() + " " + 200 + "\n");
            for (long key : vertex.keySet()) {
                double[] embedding = vertex.get(key).emb_vertex;
                String line = key + " ";
                for (double x : embedding)
                    line += x + " ";
                line = line.trim();
                writer.write(line);
                writer.write("\n");
            }
            writer.close();
        } catch (IOException e){
            e.printStackTrace();
        }
    }
    public void run(){
        rho = init_rho;
        total_samples *= 1000000;
        initAliasTable();
        initVector();
        initNegTable();
        initSigmoidTable();
        randomGenerator = new Random();
        long startTime = System.currentTimeMillis();
        trainLINE();
        long endTime = System.currentTimeMillis();
        System.out.println("Total time: " + (endTime-startTime)/1000 + "s.\n");
        //writeToTxt();
    }

    class Edge {
        long src, tgt;
        double weight;
        public Edge(long s, long t, double w){
            src = s; tgt = t; weight = w;
        }
    }
    class Vertex{
        double degree;
        double[] emb_vertex, emb_context;
        public Vertex(double w){
            degree = w;
            emb_vertex = emb_context = null;
        }
    }
}

