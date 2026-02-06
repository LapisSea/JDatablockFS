package github.lapissea.jdatablockfs;

import java.util.Arrays;

public class BrightnessMapping{
	
	private final double[] sliderPercent;
	private final double[] measuredLux;
	public final  double   luxMin, luxMax;
	
	public BrightnessMapping(double[] sliderPercent, double[] measuredLux){
		this.sliderPercent = sliderPercent;
		this.measuredLux = measuredLux;
		luxMin = Arrays.stream(measuredLux).min().orElseThrow();
		luxMax = Arrays.stream(measuredLux).max().orElseThrow();
	}
	
	public double correct(double linearPercent){
		double targetLux = luxMin + linearPercent*(luxMax - luxMin);
		return luxToPercent(targetLux);
	}
	public double luxToPercent(double targetLux){
		if(targetLux<=luxMin) return 0;
		if(targetLux>=luxMax) return 100;
		
		// find interval where targetLux falls
		for(int i = 0; i<measuredLux.length - 1; i++){
			double lx1 = measuredLux[i], lx2 = measuredLux[i + 1];
			if(targetLux>=lx1 && targetLux<=lx2){
				double p1 = sliderPercent[i], p2 = sliderPercent[i + 1];
				double t  = (targetLux - lx1)/(lx2 - lx1);
				return p1 + t*(p2 - p1);
			}
		}
		// clamp if outside range
		if(targetLux<=luxMin) return sliderPercent[0];
		return sliderPercent[sliderPercent.length - 1];
	}
}
