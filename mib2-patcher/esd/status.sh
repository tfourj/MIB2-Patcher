#!/bin/sh
# GEM entry: MIB2-Patcher status
# Runs on the RCC like the M.I.B. scripts do

on -f rcc /net/mmx/fs/sda0/mib2-patcher/patcher.sh status

# GEM closes the output when the script ends, so leave time to read it
sleep 10
