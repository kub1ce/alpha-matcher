#!/bin/sh
set -eu
java -Xms128m -Xmx1024m -cp '/work/target/classes:/work/target/dependency/*' ru.urfu.alphamatcher.experiment.ParserABMain
java -Xms128m -Xmx1024m -Dstage4.probe=true -cp '/work/target/classes:/work/target/dependency/*' ru.urfu.alphamatcher.experiment.Stage4QualityMain after
java -Xms128m -Xmx1024m -Dstage4.probe=true -cp '/work/target/stage4-baseline-classes:/work/target/dependency/*' ru.urfu.alphamatcher.experiment.Stage4QualityMain before
printf 'Stage4A reverse MIXED10k and parser diagnostic complete\n' > /results/COMPLETED.txt
