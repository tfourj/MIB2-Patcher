#!/bin/sh
# GEM entry: JOKERBTN joker button -> MENU
# Runs on the RCC like the M.I.B. scripts do

on -f rcc /net/mmx/fs/sda0/custom-patches/JOKERBTN/jokerbtn.sh set joker menu

# GEM closes the output when the script ends, so leave time to read it
sleep 5
