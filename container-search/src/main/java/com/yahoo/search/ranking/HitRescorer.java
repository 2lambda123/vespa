// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.search.ranking;

import com.yahoo.search.result.FeatureData;
import com.yahoo.search.result.Hit;
import com.yahoo.tensor.Tensor;

import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Logger;

class HitRescorer {

    private static final Logger logger = Logger.getLogger(HitRescorer.class.getName());

    private final GlobalFunEvalCtx funEvalCtx;
    private final List<NormalizerContext> normalizers;

    public HitRescorer(GlobalFunEvalCtx funEvalCtx, List<NormalizerContext> normalizers) {
        this.funEvalCtx = funEvalCtx;
        this.normalizers = normalizers;
    }

    void preprocess(WrappedHit wrapped) {
        for (var n : normalizers) {
            var evalCtx = n.inputEvalCtx();
            var scorer = evalCtx.evalSrc().get();
            double val = evalScorer(wrapped, scorer, evalCtx.fromMF());
            wrapped.setIdx(n.normalizer().addInput(val));
        }
    }

    void runNormalizers() {
        for (var n : normalizers) {
            n.normalizer().normalize();
        }
    }

    boolean rescoreHit(WrappedHit wrapped) {
        var scorer = funEvalCtx.evalSrc().get();
        for (var n : normalizers) {
            double normalizedValue = n.normalizer().getOutput(wrapped.getIdx());
            scorer.bind(n.name(), Tensor.from(normalizedValue));
        }
        double newScore = evalScorer(wrapped, scorer, funEvalCtx.fromMF());
        wrapped.setScore(newScore);
        return true;
    }

    private static double evalScorer(WrappedHit wrapped, Evaluator scorer, List<String> fromMF) {
        for (String argName : fromMF) {
            var asTensor = wrapped.getTensor(argName);
            if (asTensor != null) {
                scorer.bind(argName, asTensor);
            } else {
                logger.warning("Missing match-feature for Evaluator argument: " + argName);
                return 0.0;
            }
        }
        return scorer.evaluateScore();
    }
}
