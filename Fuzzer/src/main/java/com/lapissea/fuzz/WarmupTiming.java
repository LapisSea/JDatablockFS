package com.lapissea.fuzz;

import java.util.Arrays;

/**
 * This is a little utility for measurements and establishes a little bit more intelligent time estimation than average sum.
 * There is some work to try and cut out the characteristic JIT slow start. It's also kinda paranoid about nonsensical
 * measurement data that does not stabilize
 */
final class WarmupTiming{
	
	private static final int    MAX_WINDOW             = 384;
	private static final double MIN_SPEEDUP            = 1.25;
	private static final double MAX_DRIFT              = 0.10;
	private static final double MAX_RELATIVE_ERROR     = 0.10;
	private static final double CONFIDENCE_FACTOR      = 1.645;
	private static final int    REQUIRED_CONFIRMATIONS = 3;
	
	private final double[] initial   = new double[6];
	private final long[]   counts    = new long[MAX_WINDOW];
	private final long[]   durations = new long[MAX_WINDOW];
	
	private final Totals[] windows = {
		new Totals(6), new Totals(12), new Totals(24), new Totals(48),
		new Totals(96), new Totals(192), new Totals(MAX_WINDOW)
	};
	
	private int initialSize, size, pos, confirmations;
	private long samples, lastConfirmation;
	private double baseline, firstMean, lastMean;
	private double totalNanos, totalCount;
	private boolean warmedUp;
	
	synchronized void add(long count, long nanos){
		totalNanos += nanos;
		totalCount += count;
		if(count == 0) return;
		
		if(initialSize<initial.length){
			initial[initialSize++] = (double)nanos/count;
			if(initialSize == initial.length){
				var sorted = initial.clone();
				Arrays.sort(sorted);
				baseline = (sorted[2] + sorted[3])/2;
			}
			return;
		}
		
		for(var window : windows){
			if(size>=window.length){
				int old = Math.floorMod(pos - window.length, MAX_WINDOW);
				window.add(-counts[old], -durations[old], -1);
			}
			window.add(count, nanos, 1);
		}
		
		counts[pos] = count;
		durations[pos] = nanos;
		
		pos = (pos + 1)%MAX_WINDOW;
		if(size<MAX_WINDOW){
			size++;
		}
		samples++;
		
		for(int index = 1; index<windows.length && windows[index].length<=size; index++){
			int window = windows[index].length;
			
			var tail = statistics(windows[index], null);
			if(tail.mean<=0 || baseline<tail.mean*MIN_SPEEDUP || CONFIDENCE_FACTOR*tail.error>tail.mean*MAX_RELATIVE_ERROR){
				continue;
			}
			
			var earlier = statistics(windows[index], windows[index - 1]);
			var later   = statistics(windows[index - 1], null);
			if(Math.abs(earlier.mean - later.mean)>tail.mean*MAX_DRIFT){
				continue;
			}
			
			// Overlapping windows do not count as separate confirmations
			if(confirmations>0 && samples - window<lastConfirmation){
				return;
			}
			
			if(confirmations == 0 || !close(tail.mean, lastMean) || !close(tail.mean, firstMean)){
				confirmations = 1;
				firstMean = tail.mean;
			}else{
				confirmations++;
			}
			
			lastMean = tail.mean;
			lastConfirmation = samples;
			
			if(confirmations<REQUIRED_CONFIRMATIONS){
				return;
			}
			
			warmedUp = true;
			totalNanos = tail.nanos;
			totalCount = tail.count;
			baseline = tail.mean;
			
			// A new speedup must provide entirely new evidence. Helps with background tasks or JIT deopts
			size = confirmations = 0;
			for(var sums : windows){
				sums.clear();
			}
			return;
		}
	}
	
	private static boolean close(double a, double b){
		return Math.abs(a - b)<=(a + b)/2*MAX_DRIFT;
	}
	
	private record Statistics(double mean, double error, double nanos, double count){ }
	
	private static final class Totals{
		
		final int length;
		double nanos, count, nanosSquared, countSquared, cross;
		
		Totals(int length){ this.length = length; }
		
		void add(double count, double nanos, int sign){
			this.count += count;
			this.nanos += nanos;
			countSquared += sign*count*count;
			nanosSquared += sign*nanos*nanos;
			cross += sign*count*nanos;
		}
		
		void clear(){ nanos = count = nanosSquared = countSquared = cross = 0; }
	}
	
	private static Statistics statistics(Totals whole, Totals tail){
		int    length       = whole.length - (tail == null? 0 : tail.length);
		double nanos        = whole.nanos - (tail == null? 0 : tail.nanos);
		double count        = whole.count - (tail == null? 0 : tail.count);
		double squares      = whole.nanosSquared - (tail == null? 0 : tail.nanosSquared);
		double countSquares = whole.countSquared - (tail == null? 0 : tail.countSquared);
		double cross        = whole.cross - (tail == null? 0 : tail.cross);
		double mean         = nanos/count;
		double residuals    = Math.max(0, Math.fma(mean*mean, countSquares, Math.fma(-2*mean, cross, squares)));
		double error        = Math.sqrt(residuals*length/(length - 1))/count;
		
		return new Statistics(mean, error, nanos, count);
	}
	
	synchronized double nanosPerOp(){
		return totalCount == 0? Double.NaN : totalNanos/totalCount;
	}
	
	synchronized boolean warmedUp(){ return warmedUp; }
}
