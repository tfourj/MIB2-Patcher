#!/bin/sh
# GEM entry: TEL2ACP custom-maps
# Runs on the RCC like the M.I.B. scripts do

on -f rcc /net/mmx/mnt/app/eso/mib2-patcher/patcher.sh TEL2ACP custom-maps

# GEM closes the output when the script ends, so leave time to read it
sleep 10
