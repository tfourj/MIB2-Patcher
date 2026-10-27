#!/bin/sh
# GEM entry: JOKERBTN voice button -> Phone (TEL key)
# Runs on the RCC like the M.I.B. scripts do

on -f rcc /net/mmx/mnt/app/eso/mib2-patcher/patcher.sh JOKERBTN set voice phone

# GEM closes the output when the script ends, so leave time to read it
sleep 5
