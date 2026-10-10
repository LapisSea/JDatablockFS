package com.lapissea.fuzz.distributed.bootstrap;

import org.testng.annotations.Test;

import java.util.Optional;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Covers the identification table behind {@link OsProbe}: which probe outputs decide which OS, and
 * which ones deliberately decide nothing. Pure string logic, so it runs on any machine, with no ssh
 * server and no helper involved.
 */
public class OsProbeTest{
	
	@Test
	public void linuxIsIdentifiedByUnameAlone(){
		var answer = OsProbe.decide(Optional.of("Linux"), Optional.empty());
		
		assertEquals(answer.map(OsProbe.Answer::os), Optional.of(HostOs.LINUX));
		assertTrue(answer.orElseThrow().evidence4K().contains("uname -s"), answer.toString());
	}
	
	@Test
	public void windowsIsIdentifiedByItsVersionBanner(){
		var answer = OsProbe.decide(Optional.empty(), Optional.of("Microsoft Windows [Version 10.0.19045.3803]"));
		
		assertEquals(answer.map(OsProbe.Answer::os), Optional.of(HostOs.WINDOWS));
		assertTrue(answer.orElseThrow().evidence4K().contains("10.0.19045"), answer.toString());
	}
	
	/**
	 * Git for Windows puts a real {@code uname} on PATH, so a Windows machine can answer
	 * {@code MINGW64_NT-…}. That does not make it a POSIX helper: the POSIX bootstrap needs
	 * {@code flock}, which is not in that environment.
	 */
	@Test
	public void gitForWindowsUnameDoesNotMakeAWindowsHostPosix(){
		var answer = OsProbe.decide(Optional.of("MINGW64_NT-10.0-19045 3.4.5"),
		                            Optional.of("Microsoft Windows [Version 10.0.19045.3803]"));
		
		assertEquals(answer.map(OsProbe.Answer::os), Optional.of(HostOs.WINDOWS));
	}
	
	@Test
	public void sshBannerNoiseDoesNotHideLinux(){
		var answer = OsProbe.decide(Optional.of("post-quantum key exchange warning\nLinux"), Optional.empty());
		
		assertEquals(answer.map(OsProbe.Answer::os), Optional.of(HostOs.LINUX));
	}
	
	@Test
	public void unameWinsWhenBothProbesAnswer(){
		var answer = OsProbe.decide(Optional.of("Linux"), Optional.of("Microsoft Windows [Version 10.0.1]"));
		
		assertEquals(answer.map(OsProbe.Answer::os), Optional.of(HostOs.LINUX));
	}
	
	/**
	 * Anything else is left undecided rather than guessed: an unset {@code os} defers the decision,
	 * it does not license a coin flip. {@code OsProbe.detect} turns this into a message naming both
	 * probes and telling the operator to declare {@code os}.
	 */
	@Test
	public void unsupportedAndUnansweredProbesDecideNothing(){
		assertTrue(OsProbe.decide(Optional.of("Darwin"), Optional.empty()).isEmpty(), "macOS is not a supported helper");
		assertTrue(OsProbe.decide(Optional.of("FreeBSD"), Optional.empty()).isEmpty());
		assertTrue(OsProbe.decide(Optional.empty(), Optional.empty()).isEmpty());
		assertTrue(OsProbe.decide(Optional.of("SunOS"), Optional.of("something else")).isEmpty());
	}
}
