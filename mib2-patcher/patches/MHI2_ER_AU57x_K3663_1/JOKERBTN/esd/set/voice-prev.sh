#!/bin/sh
# GEM entry: JOKERBTN voice button -> Previous track
# Runs on the RCC like the M.I.B. scripts do

on -f rcc /net/mmx/fs/sda0/custom-patches/JOKERBTN/jokerbtn.sh set voice prev

# GEM closes the output when the script ends, so leave time to read it
sleep 5
