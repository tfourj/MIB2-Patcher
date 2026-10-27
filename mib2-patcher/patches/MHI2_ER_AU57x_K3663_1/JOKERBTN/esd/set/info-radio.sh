#!/bin/sh
# GEM entry: JOKERBTN info button -> Radio
# Runs on the RCC like the M.I.B. scripts do

on -f rcc /net/mmx/fs/sda0/custom-patches/JOKERBTN/jokerbtn.sh set info radio

# GEM closes the output when the script ends, so leave time to read it
sleep 5
