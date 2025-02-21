package tools;

import algo.*;
import algo.TerminationMode.TerminationModeType;
import io.IOUtils;
import io.LargeKIOUtils;
import io.RichFastaReader;
import ru.ifmo.genetics.dna.DnaQ;
import ru.ifmo.genetics.structures.map.BigLong2ShortHashMap;
import ru.ifmo.genetics.utils.tool.ExecutionFailedException;
import ru.ifmo.genetics.utils.tool.Parameter;
import ru.ifmo.genetics.utils.tool.Tool;
import ru.ifmo.genetics.utils.tool.inputParameterBuilder.*;
import utils.FNV1AHash;
import utils.HashFunction;
import utils.PolynomialHash;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


public class EnvironmentFinderMain extends Tool {
    public static final String NAME = "environment-finder";
    public static final String DESCRIPTION = "Finds graphic environment for many genomic sequences in given metagenomic reads";

    public static final int DEFAULT_MAX_THREADS = 32;

    public final Parameter<Integer> k = addParameter(new IntParameterBuilder("k")
            .mandatory()
            .withShortOpt("k")
            .withDescription("k-mer size")
            .create());

    public final Parameter<File[]> readsFiles = addParameter(new FileMVParameterBuilder("reads")
            .withShortOpt("i")
            .withDescription("FASTQ, BINQ, FASTA reads")
            .withDefaultValue(new File[]{})
            .create());

    public final Parameter<File> seqsFile = addParameter(new FileParameterBuilder("seq")
            .mandatory()
            .withDescription("FASTA file with sequences")
            .create());

    public final Parameter<File> hicSeqsFile = addParameter(new FileParameterBuilder("hicseq")
            .withDescription("FASTA file with Hi-C sequences")
            .create());

    public final Parameter<File> outputDir = addParameter(new FileParameterBuilder("output")
            .mandatory()
            .withShortOpt("o")
            .withDescription("output directory")
            .create());

    public final Parameter<Integer> maxKmers = addParameter(new IntParameterBuilder("maxkmers")
//            .withDefaultValue(100000)
            .withDescription("maximum number of k-mers in created subgraph")
            .create());

    public final Parameter<Integer> maxRadius = addParameter(new IntParameterBuilder("maxradius")
//            .withDefaultValue(100000)
            .withDescription("maximum distance in k-mers from starting gene")
            .create());

    public final Parameter<Integer> minCoverage = addParameter(new IntParameterBuilder("coverage")
            .withDescription("minimum depth of k-mers to consider")
            .withDefaultValue(1)
            .create());

    public final Parameter<Boolean> bothDirections = addParameter(new BoolParameterBuilder("bothdirs")
            .withDescription("run graph search in both directions from starting sequence")
            .withDefaultValue(false)
            .create());

    public final Parameter<Integer> chunkLength = addParameter(new IntParameterBuilder("chunklength")
            .withDescription("minimum node length for BLAST search")
            .withDefaultValue(1)
            .create());

    public final Parameter<Boolean> forceHashing = addParameter(new BoolParameterBuilder("forcehash")
            .withDescription("force k-mer hashing (even for k <= 31)")
            .withDefaultValue(false)
            .create());

    public final Parameter<String> hashFunction = addParameter(new StringParameterBuilder("hash")
            .withDescription("hash function to use: poly or fnv1a")
            .withDefaultValue("poly")
            .create());


    public final Parameter<Boolean> trimPaths = addParameter(new BoolParameterBuilder("trim")
            .withDescription("trim all not maximal paths?")
            .withDefaultValue(false)
            .create()
    );

    public final Parameter<Boolean> doMerge = addParameter(new BoolParameterBuilder("merge")
            .withDescription("Draw single environment for multiple input sequences?")
            .withDefaultValue(false)
            .create()
    );

    /*
    public final Parameter<Integer> percentFiltration = addParameter(new IntParameterBuilder("procfiltration")
            .mandatory()
            .withShortOpt("pf")
            .withDescription("filtration percent // [1 .. 100]")
            .withDefaultValue(1)
            .create());

    public final Parameter<Boolean> filter = addParameter(new BoolParameterBuilder("filter")
            .withDescription("do filtration on branches?")
            .withDefaultValue(false)
            .create());
    */

    private BigLong2ShortHashMap reads;
    private List<DnaQ> sequences;
    private List<DnaQ> hicSequences;
    private List<String> comments;
    private HashFunction hasher;

    public void loadInput() throws ExecutionFailedException {
        if (k.get() > 31 || forceHashing.get()) {
            info("Reading hashes of k-mers instead");
            this.hasher = LargeKIOUtils.hash = determineHashFunction();
            this.reads = LargeKIOUtils.loadReads(readsFiles.get(), k.get(), 0,
                    availableProcessors.get(), logger);
        } else {
            this.reads = IOUtils.loadReads(readsFiles.get(), k.get(), 0,
                    availableProcessors.get(), logger);
        }
        info("Hashtable size: " + this.reads.size() + " kmers");
        try {
            RichFastaReader reader = new RichFastaReader(seqsFile.get());
            this.sequences = reader.getDnas();
            this.comments = reader.getComments();
        } catch (IOException e) {
            throw new ExecutionFailedException("Could not load sequences from " + seqsFile.get().getPath());
        }
        try {
            if (hicSeqsFile.get() != null) {
                RichFastaReader reader = new RichFastaReader(hicSeqsFile.get());
                this.hicSequences = reader.getDnas();
                this.comments = reader.getComments();
            }
        } catch (IOException e) {
            throw new ExecutionFailedException("Could not load Hi-C sequences from " + hicSeqsFile.get().getPath());
        }
    }


    private HashFunction determineHashFunction() {
        if (k.get() <= 31 && !forceHashing.get()) {
            return null;
        }
        String name = hashFunction.get().toLowerCase();
        if (name.equals("fnv1a")) {
            info("Using FNV1a hash function");
            return new FNV1AHash();
        } else {
            info("Using default polynomial hash function");
            return new PolynomialHash();
        }
    }

    private TerminationMode getTerminationMode() throws ExecutionFailedException {
        TerminationMode termMode = new TerminationMode();
        if (maxKmers.get() == null && maxRadius.get() == null) {
            throw new ExecutionFailedException("At least one of --maxkmers and --maxradius parameters should be set");
        }
        if (maxKmers.get() != null) {
            termMode.addRestriction(TerminationModeType.MAX_KMERS, maxKmers.get());
        }
        if (maxRadius.get() != null) {
            termMode.addRestriction(TerminationModeType.MAX_RADIUS, maxRadius.get());
        }
        return termMode;
    }

    @Override
    protected void runImpl() throws ExecutionFailedException {
        loadInput();
        ExecutorService execService = Executors.newFixedThreadPool(availableProcessors.get());
        /* Obsolete code which filters environment based on reads coverage. Needs redesign
        if (sequences.size() == 1) {
            String outputPrefix = getOutputPrefix(0);
            String workPrefix = workDir.get().getPath() + "/";
            OneSequenceCalculator calc = new OneSequenceCalculator(sequences.get(0).toString(), k.get(),
                    minCoverage.get(), outputPrefix, workPrefix, this.hasher, reads, logger,
                    bothDirections.get(), chunkLength.get(), getTerminationMode(), trimPaths.get());
            calc.run();
            for (int i = 0; i < readsFiles.get().length; i++) {
                execService.execute(new ReadsFilter(readsFiles.get()[i], calc, outputPrefix, i, k.get(),
                        percentFiltration.get(), logger));
            }
            execService.shutdown();
            try {
                while (!execService.awaitTermination(100, TimeUnit.MINUTES)){}
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            logger.info("Reads filtration done!");
            if (filter.get()) {
                logger.info("Creating database of reads!");
                new ReadsCoverage(outputPrefix, workPrefix, readsFiles.get().length, logger).run();
                logger.info("Starting branch filtration!");
                SingleNode[] nodes = calc.filter();
                calc.createFilteredPicture(nodes);
                logger.info("Branch filtration by reads done!");
            }
        }*/

        if (!doMerge.get()) {
            for (int i = 0; i < sequences.size(); i++) {
                String outputPrefix = getOutputPrefix(i);
                String workPrefix = workDir.get().getPath() + "/";
                execService.execute(new OneSequenceCalculator(sequences.get(i).toString(), k.get(),
                        minCoverage.get(), outputPrefix, workPrefix, this.hasher, reads, logger,
                        bothDirections.get(), chunkLength.get(), getTerminationMode(), trimPaths.get()));
            }
        } else {
            info("hicSequences = " + (hicSequences == null ? 0 : hicSequences.size()));
            String outputPrefix = outputDir.get().getPath() + "/merged/";
            String workPrefix = workDir.get().getPath() + "/";
            execService.execute(new OneSequenceCalculator(sequences, k.get(),
                    minCoverage.get(), outputPrefix, workPrefix, this.hasher, reads, logger,
                    bothDirections.get(), chunkLength.get(), getTerminationMode(), trimPaths.get(), hicSequences));
        }

        execService.shutdown();
        try {
            execService.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new ExecutionFailedException("Error while building graphic environment: " + e.toString());
        }

        info("Finished processing all sequences!");
    }

    private String getOutputPrefix(int i) {
        String outputPrefix = outputDir.get().getPath() + "/";
        outputPrefix += comments.get(i) + "/";
        return outputPrefix;
    }

    @Override
    protected void cleanImpl() {

    }

    public EnvironmentFinderMain() {
        super(NAME, DESCRIPTION);
    }

    public static void main(String[] args) {
        new EnvironmentFinderMain().mainImpl(args);
    }
}
