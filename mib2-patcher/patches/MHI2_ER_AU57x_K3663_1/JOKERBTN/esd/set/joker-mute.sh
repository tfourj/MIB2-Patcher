#!/bin/sh
# GEM entry: JOKERBTN joker button -> Mute
# Runs on the RCC like the M.I.B. scripts do

on -f rcc /net/mmx/mnt/app/eso/mib2-patcher/patcher.sh JOKERBTN set joker mute

# GEM closes the output when the script ends, so leave time to read it
sleep 5
