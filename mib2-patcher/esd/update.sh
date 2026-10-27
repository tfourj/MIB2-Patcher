#!/bin/sh
# GEM entry: MIB2-Patcher update
# Runs on the RCC like the M.I.B. scripts do

on -f rcc /net/mmx/mnt/app/eso/mib2-patcher/patcher.sh update

# GEM closes the output when the script ends, so leave time to read it
sleep 10
