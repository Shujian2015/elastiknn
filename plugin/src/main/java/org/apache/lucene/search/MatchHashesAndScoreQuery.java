package org.apache.lucene.search;

import com.klibisz.elastiknn.models.HashAndFreq;
import com.klibisz.elastiknn.utils.ArrayUtils;
import org.apache.lucene.index.*;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

public class MatchHashesAndScoreQuery extends Query {

    public interface ScoreFunction {
        double score(int docId, int numMatchingHashes);
    }

    private final String field;
    private final HashAndFreq[] hashAndFrequencies;
    private final int candidates;
    private final IndexReader indexReader;
    private final Function<LeafReaderContext, ScoreFunction> scoreFunctionBuilder;
    private final int numDocsInSegment;

    public MatchHashesAndScoreQuery(final String field,
                                    final HashAndFreq[] hashAndFrequencies,
                                    final int candidates,
                                    final IndexReader indexReader,
                                    final Function<LeafReaderContext, ScoreFunction> scoreFunctionBuilder) {
        // `countMatches` expects hashes to be in sorted order.
        ArrayUtil.timSort(hashAndFrequencies);

        this.field = field;
        this.hashAndFrequencies = hashAndFrequencies;
        this.candidates = candidates;
        this.indexReader = indexReader;
        this.scoreFunctionBuilder = scoreFunctionBuilder;
        this.numDocsInSegment = indexReader.numDocs();
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) {

        return new Weight(this) {

            private short[] countMatches(LeafReaderContext context) throws IOException {
                LeafReader reader = context.reader();
                Terms terms = reader.terms(field);
                TermsEnum termsEnum = terms.iterator();
                short[] counts = new short[numDocsInSegment];
                PostingsEnum docs = null;
                for (HashAndFreq hac : hashAndFrequencies) {
                    if (termsEnum.seekExact(new BytesRef(hac.getHash()))) {
                        docs = termsEnum.postings(docs, PostingsEnum.NONE);
                        for (int i = 0; i < docs.cost(); i++) {
                            int docId = docs.nextDoc();
                            counts[docId] += Math.min(hac.getFreq(), docs.freq());
                        }
                    }
                }
                return counts;
            }

            private DocIdSetIterator buildDocIdSetIterator(short[] counts) {
                if (candidates >= numDocsInSegment) return DocIdSetIterator.all(indexReader.maxDoc());
                else {
                    int minCandidateCount = ArrayUtils.kthGreatest(counts, candidates);

                    // DocIdSetIterator that iterates over the doc ids but only emits the ids >= the min candidate count.
                    return new DocIdSetIterator() {

                        private int doc = 0;

                        @Override
                        public int docID() {
                            return doc;
                        }

                        @Override
                        public int nextDoc() {
                            // Increment doc until it exceeds the min candidate count.
                            do doc++;
                            while (doc < counts.length && counts[doc]< minCandidateCount);
                            if (doc == counts.length) return DocIdSetIterator.NO_MORE_DOCS;
                            else return docID();
                        }

                        @Override
                        public int advance(int target) {
                            while (doc < target) nextDoc();
                            return docID();
                        }

                        @Override
                        public long cost() {
                            return counts.length;
                        }
                    };
                }
            }

            @Override
            public void extractTerms(Set<Term> terms) { }

            @Override
            public Explanation explain(LeafReaderContext context, int doc) {
                return Explanation.match( 0, "If someone know what this should return, please submit a PR. :)");
            }

            @Override
            public Scorer scorer(LeafReaderContext context) throws IOException {
                ScoreFunction scoreFunction = scoreFunctionBuilder.apply(context);
                short[] counts = countMatches(context);
                DocIdSetIterator disi = buildDocIdSetIterator(counts);

                return new Scorer(this) {
                    @Override
                    public DocIdSetIterator iterator() {
                        return disi;
                    }

                    @Override
                    public float getMaxScore(int upTo) {
                        return Float.MAX_VALUE;
                    }

                    @Override
                    public float score() {
                        return (float) scoreFunction.score(docID(), counts[docID()]);
                    }

                    @Override
                    public int docID() {
                        return disi.docID();
                    }
                };
            }

            @Override
            public boolean isCacheable(LeafReaderContext ctx) {
                return false;
            }
        };
    }

    @Override
    public String toString(String field) {
        return String.format(
                "%s for field [%s] with [%d] hashes and [%d] candidates",
                this.getClass().getSimpleName(),
                this.field,
                this.hashAndFrequencies.length,
                this.candidates);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof MatchHashesAndScoreQuery) {
            MatchHashesAndScoreQuery q = (MatchHashesAndScoreQuery) obj;
            return q.hashCode() == this.hashCode();
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(field, hashAndFrequencies, candidates, indexReader, scoreFunctionBuilder);
    }
}
