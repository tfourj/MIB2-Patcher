#!/bin/sh
# GEM entry: JOKERBTN info button -> Phone (TEL key)
# Runs on the RCC like the M.I.B. scripts do

on -f rcc /net/mmx/fs/sda0/mib2-patcher/patches/MHI2_ER_AU57x_K3663_1/JOKERBTN/jokerbtn.sh set info phone

# GEM closes the output when the script ends, so leave time to read it
sleep 5
