#!/bin/sh
# GEM entry: MENUSCREEN slot breakdown
# Runs on the RCC like the M.I.B. scripts do

on -f rcc /net/mmx/fs/sda0/custom-patches/MENUSCREEN/menuscreen.sh slot breakdown

# GEM closes the output when the script ends, so leave time to read it
sleep 10
