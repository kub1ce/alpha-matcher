#!/bin/sh
set -eu
MAIN=ru.urfu.alphamatcher.experiment.Stage4BQualityMain
BEFORE='/work/target/stage4b-baseline-classes:/work/target/dependency/*'
AFTER='/work/target/classes:/work/target/dependency/*'
java -Xms128m -Xmx1024m -Dstage4b.qualityOnly=true -cp "$BEFORE" "$MAIN" before
java -Xms128m -Xmx1024m -Dstage4b.qualityOnly=true -cp "$AFTER" "$MAIN" after
java -Xms128m -Xmx1024m -Dstage4b.skipQuality=true -cp "$BEFORE" "$MAIN" before
java -Xms128m -Xmx1024m -Dstage4b.skipQuality=true -cp "$AFTER" "$MAIN" after
printf 'Stage4B.1 before/after completed; streaming/100k/v4 pending\n' > /results/COMPLETED.txt
