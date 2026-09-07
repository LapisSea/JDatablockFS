package com.lapissea.dfs.run.repro;

import com.lapissea.fuzz.FuzzSequence;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduction test for BUG-01: FuzzSequence seed sign extension.
 *
 * FuzzSequence.read() (Fuzzer/.../fuzz/FuzzSequence.java:51-55) reads the seed's
 * numObytes into a zero-initialized 8-byte little-endian buffer WITHOUT sign-extending
 * the last byte. A negative seed that does not fill all 8 bytes therefore round-trips
 * to a large positive value (e.g. seed=-5 is encoded as the single byte 0xFB and read
 * back as 251). Since seeds from FuzzSequenceSource (seed ^ mixMurmur64(idx)) are
 * negative about half the time, roughly half of all saved fuzz failures replay with a
 * different seed and will not reproduce.
 *
 * This test FAILS on the current code for every negative seed needing fewer than 8
 * bytes (the Long.MIN_VALUE control case, which uses all 8 bytes, passes).
 */
public class ReproFuzzSignExtTests{

	@DataProvider(name = "negativeSeeds")
	Object[][] negativeSeeds(){
		return new Object[][]{
			{-5L},             // 1-byte encoding (0xFB) -> read back as 251
			{-1L},             // 1-byte encoding (0xFF) -> read back as 255
			{-129L},           // 2-byte encoding -> read back as 65415
			{-300L},
			{-123456L},
			{-999999999L},
			{Long.MIN_VALUE},  // needs all 8 bytes -> no truncation, round-trips correctly (control case)
		};
	}

	@Test(dataProvider = "negativeSeeds")
	void negativeSeedRoundTrip(long seed){
		var original = new FuzzSequence(0, 0, seed, 100);
		var stick = original.makeDataStick();
		var back = FuzzSequence.fromDataStick(stick);
		assertThat(back.seed())
			.as("round-tripped seed (original=%d, stick=%s)", seed, stick)
			.isEqualTo(seed);
		assertThat(back)
			.as("full sequence round trip (stick=%s)", stick)
			.isEqualTo(original);
	}

	@Test
	void positiveSeedRoundTripSanity(){
		var original = new FuzzSequence(0, 0, 5L, 100);
		var stick = original.makeDataStick();
		assertThat(FuzzSequence.fromDataStick(stick))
			.as("positive seed sanity (stick=%s)", stick)
			.isEqualTo(original);
	}
}
