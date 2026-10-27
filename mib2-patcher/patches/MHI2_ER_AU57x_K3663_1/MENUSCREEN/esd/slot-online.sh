#!/bin/sh
# GEM entry: MENUSCREEN slot online
# Runs on the RCC like the M.I.B. scripts do

on -f rcc /net/mmx/mnt/app/eso/mib2-patcher/patcher.sh MENUSCREEN slot online

# GEM closes the output when the script ends, so leave time to read it
sleep 10
