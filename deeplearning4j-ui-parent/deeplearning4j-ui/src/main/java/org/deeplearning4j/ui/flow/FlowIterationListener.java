package org.deeplearning4j.ui.flow;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.deeplearning4j.api.storage.Persistable;
import org.deeplearning4j.api.storage.StatsStorage;
import org.deeplearning4j.api.storage.StatsStorageRouter;
import org.deeplearning4j.nn.api.Layer;
import org.deeplearning4j.nn.api.Model;
import org.deeplearning4j.nn.conf.layers.BaseOutputLayer;
import org.deeplearning4j.nn.conf.layers.FeedForwardLayer;
import org.deeplearning4j.nn.conf.layers.SubsamplingLayer;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.graph.vertex.GraphVertex;
import org.deeplearning4j.nn.graph.vertex.VertexIndices;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.optimize.api.IterationListener;
import org.deeplearning4j.ui.UiConnectionInfo;
import org.deeplearning4j.ui.UiUtils;
import org.deeplearning4j.ui.api.UIServer;
import org.deeplearning4j.ui.flow.beans.*;
import org.deeplearning4j.ui.flow.data.FlowStaticPersistable;
import org.deeplearning4j.ui.flow.data.FlowUpdatePersistable;
import org.deeplearning4j.ui.stats.StatsListener;
import org.deeplearning4j.ui.storage.mapdb.MapDBStatsStorage;
import org.deeplearning4j.ui.weights.HistogramBin;
import org.deeplearning4j.util.UIDProvider;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.shape.Shape;
import org.nd4j.linalg.util.ArrayUtil;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This IterationListener is suited for general model performance/architecture overview
 *
 * @deprecated Use {@link StatsListener} and {@link UIServer#attach(StatsStorage)}. See examples repo for how.
 *
 * @author raver119@gmail.com
 */
@Slf4j
@Deprecated
public class FlowIterationListener implements IterationListener {

    private static final String FORMAT = "%02d:%02d:%02d";
    public static final String INPUT = "INPUT";
    private int frequency = 1;
    private boolean firstIteration = true;
    private ModelState modelState = new ModelState();

    private AtomicLong iterationCount = new AtomicLong(0);


    private long lastTime = System.currentTimeMillis();
    private long currTime;
    private long initTime = System.currentTimeMillis();

    private static final List<String> colors = Collections.unmodifiableList(
                    Arrays.asList("#9966ff", "#ff9933", "#ffff99", "#3366ff", "#0099cc", "#669999", "#66ffff"));

    private final StatsStorageRouter ssr;
    private final String sessionID;
    private final String workerID;
    private boolean openBrowser;

    protected FlowIterationListener() {
        this(1);
        // please keep this constructor protected
    }

    /**
     * Creates IterationListener and attaches it local UIServer instance
     *
     * @param frequency update frequency
     */
    public FlowIterationListener(int frequency) {
        this(new MapDBStatsStorage(), frequency, null, null, true);
    }

    @Deprecated
    public FlowIterationListener(@NonNull String address, int port, int frequency) {
        this(frequency);
    }

    public FlowIterationListener(StatsStorageRouter ssr, int frequency, String sessionID, String workerID,
                    boolean openBrowser) {
        this.frequency = frequency;

        this.ssr = ssr;
        if (sessionID == null) {
            this.sessionID = UUID.randomUUID().toString();
        } else {
            this.sessionID = sessionID;
        }
        if (workerID == null) {
            this.workerID = UIDProvider.getJVMUID() + "_" + Thread.currentThread().getId();
        } else {
            this.workerID = workerID;
        }

        this.openBrowser = openBrowser;
        if (ssr instanceof StatsStorage && openBrowser) {
            UIServer.getInstance().attach((StatsStorage) ssr);
        }

        System.out.println(
                        "FlowIterationListener path: http://localhost:" + UIServer.getInstance().getPort() + "/flow");
    }


    @Deprecated
    public FlowIterationListener(@NonNull UiConnectionInfo connectionInfo, int frequency) {
        this(frequency);
    }

    /**
     * Get if listener invoked
     */
    @Override
    public boolean invoked() {
        return false;
    }

    /**
     * Change invoke to true
     */
    @Override
    public void invoke() {

    }

    /**
     * Event listener for each iteration
     *
     * @param model     the model iterating
     * @param iteration the iteration
     */
    @Override
    public synchronized void iterationDone(Model model, int iteration) {
        if (iterationCount.incrementAndGet() % frequency == 0) {
            currTime = System.currentTimeMillis();
            /*
            Basic plan:
                1. We should detect, if that's CompGraph or MultilayerNetwork. However the actual difference will be limited to number of non-linear connections.
                2. Network structure should be converted to JSON
                3. Params for each node should be packed to JSON as well
                4. For specific cases (like CNN) binary data should be wrapped into base64
                5. For arrays/params gzip could be used (to be investigated)
                ......
                Later, on client side, this JSON should be parsed and rendered. So, proper object structure to be considered.
             */

            if (firstIteration) {
                // On first pass we just build list of layers. However, for MultiLayerNetwork first pass is the last pass, since we know connections in advance
                ModelInfo info = buildModelInfo(model);

                // send ModelInfo to stats storage
                Persistable staticInfo =
                                new FlowStaticPersistable(sessionID, workerID, System.currentTimeMillis(), info);
                ssr.putStaticInfo(staticInfo);
            }


            // update modelState
            buildModelState(model);
            Persistable updateInfo =
                            new FlowUpdatePersistable(sessionID, workerID, System.currentTimeMillis(), modelState);
            ssr.putUpdate(updateInfo);


            if (firstIteration && openBrowser) {
                UIServer uiServer = UIServer.getInstance();
                String path = "http://localhost:" + uiServer.getPort() + "/flow?sid=" + sessionID;
                try {
                    UiUtils.tryOpenBrowser(path, log);
                } catch (Exception e) {
                }
                firstIteration = false;
            }
        }

        lastTime = System.currentTimeMillis();
    }

    /**
     * This method returns all Layers connected to the currentInput
     *
     * @param vertices
     * @param currentInput
     * @param currentY
     * @return
     */
    protected List<LayerInfo> flattenToY(ModelInfo model, GraphVertex[] vertices, List<String> currentInput,
                    int currentY) {
        List<LayerInfo> results = new ArrayList<>();
        int x = 0;
        for (int v = 0; v < vertices.length; v++) {
            GraphVertex vertex = vertices[v];
            VertexIndices[] indices = vertex.getInputVertices();

            if (indices != null)
                for (int i = 0; i < indices.length; i++) {
                    GraphVertex cv = vertices[indices[i].getVertexIndex()];
                    String inputName = cv.getVertexName();

                    for (String input : currentInput) {
                        if (inputName.equals(input)) {
                            // we have match for Vertex
                            //    log.info("Vertex: " + vertex.getVertexName() + " has Input: " + input);
                            try {
                                LayerInfo info = model.getLayerInfoByName(vertex.getVertexName());
                                if (info == null)
                                    info = getLayerInfo(vertex.getLayer(), x, currentY, 121);
                                info.setName(vertex.getVertexName());

                                // special case here: vertex isn't a layer
                                if (vertex.getLayer() == null) {
                                    info.setLayerType(vertex.getClass().getSimpleName());
                                }
                                if (info.getName().endsWith("-merge"))
                                    info.setLayerType("MERGE");
                                if (model.getLayerInfoByName(vertex.getVertexName()) == null) {
                                    x++;
                                    model.addLayer(info);
                                    results.add(info);
                                }

                                // now we should map connections
                                LayerInfo connection = model.getLayerInfoByName(input);
                                if (connection != null) {
                                    connection.addConnection(info);
                                    //  log.info("Adding connection ["+ connection.getName()+"] -> ["+ info.getName()+"]");
                                } else {
                                    // the only reason to have null here, is direct input connection
                                    //connection.addConnection(0,0);
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
        }
        return results;
    }

    protected void buildModelState(Model model) {
        // first we update performance state
        long timeSpent = currTime - lastTime;
        float timeSec = timeSpent / 1000f;

        INDArray input = model.input();
        long tadLength = Shape.getTADLength(input.shape(), ArrayUtil.range(1, input.rank()));

        long numSamples = input.lengthLong() / tadLength;

        modelState.addPerformanceSamples(numSamples / timeSec);
        modelState.addPerformanceBatches(1 / timeSec);
        modelState.setIterationTime(timeSpent);

        // now model score
        modelState.addScore((float) model.score());
        modelState.setScore((float) model.score());

        modelState.setTrainingTime(parseTime(System.currentTimeMillis() - initTime));

        // and now update model params/gradients
        Map<String, Map> newGrad = new LinkedHashMap<>();

        Map<String, Map> newParams = new LinkedHashMap<>();
        Map<String, INDArray> params = model.paramTable();

        Layer[] layers = null;
        if (model instanceof MultiLayerNetwork) {
            layers = ((MultiLayerNetwork) model).getLayers();
        } else if (model instanceof ComputationGraph) {
            layers = ((ComputationGraph) model).getLayers();
        }

        List<Double> lrs = new ArrayList<>();
        if (layers != null) {
            for (Layer layer : layers) {
                lrs.add(layer.conf().getLayer().getLearningRate());
            }
            modelState.setLearningRates(lrs);
        }
        Map<Integer, LayerParams> layerParamsMap = new LinkedHashMap<>();

        for (Map.Entry<String, INDArray> entry : params.entrySet()) {
            String param = entry.getKey();
            if (!Character.isDigit(param.charAt(0)))
                continue;

            int layer = Integer.parseInt(param.replaceAll("\\_.*$", ""));
            String key = param.replaceAll("^.*?_", "").toLowerCase();

            if (!layerParamsMap.containsKey(layer))
                layerParamsMap.put(layer, new LayerParams());

            HistogramBin histogram =
                            new HistogramBin.Builder(entry.getValue().dup()).setBinCount(14).setRounding(6).build();

            // TODO: something better would be nice to have here
            if (key.equalsIgnoreCase("w")) {
                layerParamsMap.get(layer).setW(histogram.getData());
            } else if (key.equalsIgnoreCase("rw")) {
                layerParamsMap.get(layer).setRW(histogram.getData());
            } else if (key.equalsIgnoreCase("rwf")) {
                layerParamsMap.get(layer).setRWF(histogram.getData());
            } else if (key.equalsIgnoreCase("b")) {
                layerParamsMap.get(layer).setB(histogram.getData());
            }
        }
        modelState.setLayerParams(layerParamsMap);
    }

    protected ModelInfo buildModelInfo(Model model) {
        ModelInfo modelInfo = new ModelInfo();
        if (model instanceof ComputationGraph) {
            ComputationGraph graph = (ComputationGraph) model;

            /*
                we assume that graph starts on input. every layer connected to input - is on y1
                every layer connected to y1, is on y2 etc.
              */
            List<String> inputs = graph.getConfiguration().getNetworkInputs();
            // now we need to add inputs as y0 nodes
            int x = 0;
            for (String input : inputs) {
                GraphVertex vertex = graph.getVertex(input);

                long numSamples;
                long tadLength;
                if (vertex.getInputs() == null || vertex.getInputs().length == 0) {
                    numSamples = 0;
                    tadLength = 0;
                } else {
                    INDArray gInput = vertex.getInputs()[0];
                    tadLength = Shape.getTADLength(gInput.shape(), ArrayUtil.range(1, gInput.rank()));
                    numSamples = gInput.lengthLong() / tadLength;
                }



                StringBuilder builder = new StringBuilder();
                builder.append("Vertex name: ").append(input).append("<br/>");
                builder.append("Model input").append("<br/>");
                builder.append("Input size: ").append(tadLength).append("<br/>");
                builder.append("Batch size: ").append(numSamples).append("<br/>");

                LayerInfo info = new LayerInfo();
                info.setId(0);
                info.setName(input);
                info.setY(0);
                info.setX(x);
                info.setLayerType(INPUT);
                info.setDescription(new Description());
                info.getDescription().setMainLine("Model input");
                info.getDescription().setText(builder.toString());
                modelInfo.addLayer(info);
                x++;
            }

            GraphVertex[] vertices = graph.getVertices();

            // filling grid in LTR/TTB direction
            List<String> needle = new ArrayList<>();


            // we assume that max row can't be higher then total number of vertices
            for (int y = 1; y < vertices.length; y++) {
                if (needle.isEmpty())
                    needle.addAll(inputs);

                /*
                    for each grid row we look for nodes, that are connected to previous layer
                */
                List<LayerInfo> layersForGridY = flattenToY(modelInfo, vertices, needle, y);

                needle.clear();
                for (LayerInfo layerInfo : layersForGridY) {
                    needle.add(layerInfo.getName());
                }
                if (needle.isEmpty())
                    break;
            }

        } else if (model instanceof MultiLayerNetwork) {
            MultiLayerNetwork network = (MultiLayerNetwork) model;

            // manually adding input layer

            INDArray input = model.input();
            long tadLength = Shape.getTADLength(input.shape(), ArrayUtil.range(1, input.rank()));

            long numSamples = input.lengthLong() / tadLength;

            StringBuilder builder = new StringBuilder();
            builder.append("Model input").append("<br/>");
            builder.append("Input size: ").append(tadLength).append("<br/>");
            builder.append("Batch size: ").append(numSamples).append("<br/>");

            LayerInfo info = new LayerInfo();
            info.setId(0);
            info.setName("Input");
            info.setY(0);
            info.setX(0);
            info.setLayerType(INPUT);
            info.setDescription(new Description());
            info.getDescription().setMainLine("Model input");
            info.getDescription().setText(builder.toString());
            info.addConnection(0, 1);
            modelInfo.addLayer(info);


            // entry 0 is reserved for inputs
            int y = 1;

            // for MLN x value is always 0
            final int x = 0;
            for (Layer layer : network.getLayers()) {
                LayerInfo layerInfo = getLayerInfo(layer, x, y, y);
                // since it's MLN, we know connections in advance as curLayer + 1
                layerInfo.addConnection(x, y + 1);
                modelInfo.addLayer(layerInfo);
                y++;
            }

            LayerInfo layerInfo = modelInfo.getLayerInfoByCoords(x, y - 1);
            layerInfo.dropConnections();

        } // else throw new IllegalStateException("Model ["+model.getClass().getCanonicalName()+"] doesn't looks like supported one.");

        // find layers without connections, and mark them as output layers
        for (LayerInfo layerInfo : modelInfo.getLayers()) {
            if (layerInfo.getConnections().size() == 0)
                layerInfo.setLayerType("OUTPUT");
        }

        // now we apply colors to distinct layer types
        AtomicInteger cnt = new AtomicInteger(0);
        for (String layerType : modelInfo.getLayerTypes()) {
            String curColor = colors.get(cnt.getAndIncrement());
            if (cnt.get() >= colors.size())
                cnt.set(0);
            for (LayerInfo layerInfo : modelInfo.getLayersByType(layerType)) {
                if (layerType.equals(INPUT)) {
                    layerInfo.setColor("#99ff66");
                } else if (layerType.equals("OUTPUT")) {
                    layerInfo.setColor("#e6e6e6");
                } else {
                    layerInfo.setColor(curColor);
                }
            }
        }
        return modelInfo;
    }

    private LayerInfo getLayerInfo(Layer layer, int x, int y, int order) {
        LayerInfo info = new LayerInfo();


        // set coordinates
        info.setX(x);
        info.setY(y);

        // if name was set, we should grab it
        try {
            info.setName(layer.conf().getLayer().getLayerName());
        } catch (Exception e) {
        }
        if (info.getName() == null || info.getName().isEmpty())
            info.setName("unnamed");

        // unique layer id required here
        info.setId(order);

        // set layer description according to layer params
        Description description = new Description();
        info.setDescription(description);

        // set layer type
        try {
            info.setLayerType(layer.getClass().getSimpleName().replaceAll("Layer$", ""));
        } catch (Exception e) {
            info.setLayerType("n/a");
            return info;
        }


        StringBuilder mainLine = new StringBuilder();
        StringBuilder subLine = new StringBuilder();
        StringBuilder fullLine = new StringBuilder();

        //    log.info("Layer: " + info.getName() + " class: " + layer.getClass().getSimpleName());

        if (layer.type().equals(Layer.Type.CONVOLUTIONAL)) {
            org.deeplearning4j.nn.conf.layers.ConvolutionLayer layer1 =
                            (org.deeplearning4j.nn.conf.layers.ConvolutionLayer) layer.conf().getLayer();
            mainLine.append("K: " + Arrays.toString(layer1.getKernelSize()) + " S: "
                            + Arrays.toString(layer1.getStride()) + " P: " + Arrays.toString(layer1.getPadding()));
            subLine.append("nIn/nOut: [" + layer1.getNIn() + "/" + layer1.getNOut() + "]");
            fullLine.append("Kernel size: ").append(Arrays.toString(layer1.getKernelSize())).append("<br/>");
            fullLine.append("Stride: ").append(Arrays.toString(layer1.getStride())).append("<br/>");
            fullLine.append("Padding: ").append(Arrays.toString(layer1.getPadding())).append("<br/>");
            fullLine.append("Inputs number: ").append(layer1.getNIn()).append("<br/>");
            fullLine.append("Outputs number: ").append(layer1.getNOut()).append("<br/>");
        } else if (layer.conf().getLayer() instanceof SubsamplingLayer) {
            SubsamplingLayer layer1 = (SubsamplingLayer) layer.conf().getLayer();
            fullLine.append("Kernel size: ").append(Arrays.toString(layer1.getKernelSize())).append("<br/>");
            fullLine.append("Stride: ").append(Arrays.toString(layer1.getStride())).append("<br/>");
            fullLine.append("Padding: ").append(Arrays.toString(layer1.getPadding())).append("<br/>");
            fullLine.append("Pooling type: ").append(layer1.getPoolingType().toString()).append("<br/>");
        } else if (layer.conf().getLayer() instanceof FeedForwardLayer) {
            org.deeplearning4j.nn.conf.layers.FeedForwardLayer layer1 =
                            (org.deeplearning4j.nn.conf.layers.FeedForwardLayer) layer.conf().getLayer();
            mainLine.append("nIn/nOut: [" + layer1.getNIn() + "/" + layer1.getNOut() + "]");
            subLine.append(info.getLayerType());
            fullLine.append("Inputs number: ").append(layer1.getNIn()).append("<br/>");
            fullLine.append("Outputs number: ").append(layer1.getNOut()).append("<br/>");
        } else {
            // TODO: Introduce Layer.Type.OUTPUT
            if (layer instanceof BaseOutputLayer) {
                mainLine.append("Outputs: ["
                                + ((org.deeplearning4j.nn.conf.layers.BaseOutputLayer) layer.conf().getLayer())
                                                .getNOut()
                                + "]");
                fullLine.append("Outputs number: ").append(
                                ((org.deeplearning4j.nn.conf.layers.BaseOutputLayer) layer.conf().getLayer()).getNOut())
                                .append("<br/>");
            }
        }

        subLine.append(" A: [").append(layer.conf().getLayer().getActivationFn().toString()).append("]");
        fullLine.append("Activation function: ").append("<b>")
                        .append(layer.conf().getLayer().getActivationFn().toString()).append("</b>").append("<br/>");

        description.setMainLine(mainLine.toString());
        description.setSubLine(subLine.toString());
        description.setText(fullLine.toString());

        return info;
    }

    protected String parseTime(long milliseconds) {
        return String.format(FORMAT, TimeUnit.MILLISECONDS.toHours(milliseconds),
                        TimeUnit.MILLISECONDS.toMinutes(milliseconds)
                                        - TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(milliseconds)),
                        TimeUnit.MILLISECONDS.toSeconds(milliseconds)
                                        - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(milliseconds)));
    }
}
