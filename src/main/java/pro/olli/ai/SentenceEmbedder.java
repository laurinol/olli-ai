/*
 * Copyright (c) 2025 Kai Laurinolli. All rights reserved.
 */

package pro.olli.ai;

/*
import org.deeplearning4j.nn.api.Model;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.onnximport.ONNXModelLoader;
import org.nd4j.linalg.factory.Nd4j;

public class SentenceEmbedder {
    private ComputationGraph model;

    public SentenceEmbedder() throws Exception {
        ONNXModelLoader loader = new ONNXModelLoader();
        model = (ComputationGraph) loader.loadModel("src/main/resources/minilm-l6-v2.onnx");
        model.init();
    }

    public INDArray getEmbedding(String text) {
        // Tokenize manually (simplified; use a Java tokenizer like OpenNLP)
        String[] tokens = text.split("\\s+");
        INDArray inputIds = Nd4j.create(tokens.length, 1).assign(101); // Dummy token IDs (needs real tokenizer)
        INDArray attentionMask = Nd4j.ones(tokens.length, 1);
        INDArray output = model.output(inputIds, attentionMask)[0]; // Simplified
        return output.mean(0); // Pooling to get sentence embedding
    }

    public static void main(String[] args) throws Exception {
        SentenceEmbedder embedder = new SentenceEmbedder();
        INDArray embedding = embedder.getEmbedding("Test sentence");
        System.out.println(embedding); // 384-dim vector
    }
}
*/
