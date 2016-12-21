package org.icij.datashare.text.nlp.gate;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiFunction;

import static java.util.Arrays.asList;

import gate.AnnotationSet;
import gate.util.GateException;
import gate.creole.ResourceInstantiationException;
import es.upm.oeg.icij.entityextractor.GATENLPDocument;
import es.upm.oeg.icij.entityextractor.GATENLPFactory;
import es.upm.oeg.icij.entityextractor.GATENLPApplication;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import static org.icij.datashare.text.Language.*;
import org.icij.datashare.text.NamedEntity;
import static org.icij.datashare.text.NamedEntity.Category.LOCATION;
import static org.icij.datashare.text.NamedEntity.Category.ORGANIZATION;
import static org.icij.datashare.text.NamedEntity.Category.PERSON;
import org.icij.datashare.text.nlp.AbstractNlpPipeline;
import org.icij.datashare.text.nlp.NlpStage;
import static org.icij.datashare.text.nlp.NlpStage.*;
import org.icij.datashare.text.nlp.Annotation;


/**
 * {@link org.icij.datashare.text.nlp.NlpPipeline}
 * {@link org.icij.datashare.text.nlp.AbstractNlpPipeline}
 * {@link Type#GATE}
 *
 * <a href="https://github.com/ICIJ/entity-extractor/tree/production">OEG UPM Gate-based entity-extractor</a>
 * <a href="https://gate.ac.uk/">Gate</a>
 * JAPE rules defined in {@code src/main/resources/org/icij/datashare/text/nlp/gate/ner/rules/} (to evolve)
 *
 * Created by julien on 5/19/16.
 */
public final class GateNlpPipeline extends AbstractNlpPipeline {

    private static final Map<Language, Set<NlpStage>> SUPPORTED_STAGES =
            new HashMap<Language, Set<NlpStage>>(){{
                put(ENGLISH, new HashSet<>(asList(TOKEN, NER)));
                put(SPANISH, new HashSet<>(asList(TOKEN, NER)));
                put(FRENCH,  new HashSet<>(asList(TOKEN, NER)));
                put(GERMAN,  new HashSet<>(asList(TOKEN, NER)));
            }};

    // Resources base directory (configuration, dictionaries, rule-based grammar)
    private static final Path RESOURCES_DIR = Paths.get(
            System.getProperty("user.dir"), "src", "main", "resources",
            Paths.get( GateNlpPipeline.class.getPackage().getName().replace(".", "/") ).toString()
    );

    // NamedEntityCategory to Gate annotation types
    private static final Map<NamedEntity.Category, String> GATE_NER_CATEGORY_NAME =
            new HashMap<NamedEntity.Category, String>(){{
                put(ORGANIZATION, "Company");
                put(PERSON,       "Person");
                put(LOCATION,     "Country");
            }};

    private static final Map<NlpStage, String> GATE_STAGE_NAME =
            new HashMap<NlpStage, String>(){{
                put(TOKEN,    "Token");
                put(SENTENCE, "Sentence");
            }};


    // Gate annotator
    private GATENLPApplication pipeline;


    public GateNlpPipeline(Properties properties) {
        super(properties);
        // TOKEN <-- NER
        stageDependencies.get(NER).add(TOKEN);
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public Map<Language, Set<NlpStage>> supportedStages() {
        return SUPPORTED_STAGES;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean initialize(Language language) {
        if ( ! super.initialize(language)) {
            return false;
        }
        // Already loaded?
        if (pipeline != null) {
            return true;
        }
        try {
            // Load and store pipeline
            pipeline = GATENLPFactory.create(RESOURCES_DIR.toFile());

        } catch (GateException | IOException e) {
            LOGGER.error("Failed to build GateNLP Application", e);
            return false;
        }
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Optional<Annotation> process(String input, String hash, Language language) {
        Annotation annotation = new Annotation(hash, getType(), language);

        try {
            // Gate annotated document
            String gateDocName = String.join(".", asList(Document.HASHER.hash(input), "txt"));
            GATENLPDocument gateDoc = new GATENLPDocument(gateDocName, input);

            // Tokenize input
            // NER input
            LOGGER.info("Tokenizing, Name-finding - " + Thread.currentThread().getName());
            pipeline.annotate(gateDoc);
            gateDoc.storeAnnotationSet();
            gateDoc.cleanDocument();

            // Feed annotation
            AnnotationSet tokenAnnotationSet = gateDoc.getAnnotationSet(GATE_STAGE_NAME.get(TOKEN));
            if (tokenAnnotationSet != null) {
                for (gate.Annotation gateAnnotation : new ArrayList<>(tokenAnnotationSet)) {
                    String word          = gateAnnotation.getFeatures().get("string").toString();
                    int tokenOffsetBegin = gateAnnotation.getStartNode().getOffset().intValue();
                    int tokenOffsetEnd   = gateAnnotation.getEndNode().getOffset().intValue();
                    annotation.add(TOKEN, tokenOffsetBegin, tokenOffsetEnd);
                }
            }

            // Feed annotation
            if (targetStages.contains(NER)) {
                BiFunction<GATENLPDocument, NamedEntity.Category, AnnotationSet> nerAnnotationSet =
                        (doc, category) -> doc.getAnnotationSet(GATE_NER_CATEGORY_NAME.get(category));
                targetEntities.stream()
                        .filter  ( category ->  nerAnnotationSet.apply(gateDoc, category) != null )
                        .forEach ( category -> {
                            for (gate.Annotation gateAnnotation : nerAnnotationSet.apply(gateDoc, category).inDocumentOrder()) {
                                String nerMention     = gateAnnotation.getFeatures().get("string").toString();
                                int    nerOffsetBegin = gateAnnotation.getStartNode().getOffset().intValue();
                                int    nerOffsetEnd   = gateAnnotation.getEndNode().getOffset().intValue();
                                annotation.add(NER, nerOffsetBegin, nerOffsetEnd, category.toString());
                            }
                        });
            }
            return Optional.of( annotation );

        } catch (ResourceInstantiationException e) {
            LOGGER.error("Failed to createList Gate Document", e);
            return Optional.empty();
        }

    }

    @Override
    protected void terminate(Language language) {
        super.terminate(language);
        // (Don't) keep pipeline
        if ( ! caching) {
            // Release annotators
            pipeline.cleanApplication();
            // Release handle
            pipeline = null;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        if (pipeline != null) {
            pipeline.cleanApplication();
            pipeline = null;
        }
    }

    @Override
    public Optional<String> getPosTagSet() {
        return Optional.empty();
    }

}
