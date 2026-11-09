package ru.urfu.alphamatcher.experiment;
/** Test-only access to the historical scenario mapping, never a runtime matching dependency. */
public final class HistoricalOracle {
    public static ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator.Truth reference(
        ru.urfu.alphamatcher.experiment.synthetic.SyntheticGenerator.Truth truth){return Stage3QualityMain.referenceV3(truth);}
}
