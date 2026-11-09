#!/bin/sh
set -eu
java -Xms128m -Xmx1024m -cp '/work/target/stage4-baseline-classes:/work/target/dependency/*' ru.urfu.alphamatcher.experiment.Stage4QualityMain before
java -Xms128m -Xmx1024m -cp '/work/target/classes:/work/target/dependency/*' ru.urfu.alphamatcher.experiment.Stage4QualityMain after
printf 'Stage4A before/after completed; v4 not prepared or opened\n' > /results/COMPLETED.txt
