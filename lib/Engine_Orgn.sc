// toy keyboard inspired fm synth + lofi effect (ulaw) - ulaw will also process ADC in.

/*
todo: engine.trig(), engine.noteTrig(), engine.noteTrigGlide()
*/

Engine_Orgn : CroneEngine {

    var <gator;
    var <fx;
    var <fxBus;
    var <ops = 3;

	*new { arg context, doneCallback;
		^super.new(context, doneCallback);
	}

	alloc {

	    //fm synth synthdef - number of operators is set by ops
        var fmDef = SynthDef.new(\fm, {

            //named controls (inputs) - most are multichannel (channel per operator). 
            var gate = \gate.kr(0), pan = \pan.kr(0), hz = \hz.kr([440, 440, 0]),
            amp = \amp.kr(Array.fill(ops, { arg i; 1/(2.pow(i)) })), vel = \velocity.kr(1),
            ratio = \ratio.kr(Array.fill(ops, { arg i; (2.pow(i)) })),
            mod = Array.fill(ops, { arg i; NamedControl.kr(\mod ++ i, 0!ops) }),
        	a = \attack.kr(0.001!ops), d = \decay.kr(0!ops), s = \sustain.kr(1!ops),
            r = \release.kr(0.2!ops), curve = \curve.kr(-4), 
            done = \done.kr(1!ops); // done needs to be set based on release times (longest relsease channel gets a 1)

            //hz envelope (for glides)
            var frq = XLine.kr(hz[0], hz[1], hz[2], doneAction:0);

            //the synth sound (3 lines!!!)
        	var env = EnvGen.kr(Env.adsr(a, d, s, r, 1, curve), gate, doneAction: (done * 2)); //adsr envelopes
        	var osc = SinOsc.ar(frq * ratio, Mix.ar(LocalIn.ar(ops) * mod)) * env; //oscs from the last cycle phase modulate current cycle
        	LocalOut.ar(osc); //send oscs to the nest cycle

        	//pan/amp/output
            Out.ar(\outbus.kr(0), Pan2.ar(Mix.ar((osc / ops) * amp * vel * 0.15), pan));
        }).add;

        //analog waveshaper table by @ganders
        var tf = (Env([-0.7, 0, 0.7], [1,1], [8,-8]).asSignal(1025) + (
            Signal.sineFill(
                1025,
                (0!3) ++ [0,0,1,1,0,1].scramble,
                    {rrand(0,2pi)}!9
                )/10;
        )).normalize;
        var tfBuf = Buffer.loadCollection(context.server, tf.asWavetableNoWrap);

        //ulaw synthdef
        var fxDef = SynthDef.new(\ulaw, {
            var in = Mix.ar([In.ar(\inbus.kr(), 2), SoundIn.ar([0,1])]),
            u = 2.pow(\bits.kr(11)), r = 700, lim = 1,
            samps = \samples.kr(26460), dust = \dustiness.kr(1.95);
            var sig = in;

            sig = Mix.ar([
                sig,
                EnvFollow.ar(in, 0.99) * Mix.ar([
                    GrayNoise.ar(1) * Dust.ar(dust) * \dust.kr(1), //add dust
                    Crackle.ar(dust) * \crackle.kr(0.1) //add crackle
                ])
            ]);
            sig = LPF.ar(sig, samps/2); //anti-aliasing filter
            sig = Compander.ar(sig, sig, //limiter/compression
                thresh: 1,
                slopeBelow: 1,
                slopeAbove: 0.5,
                clampTime:  0.01,
                relaxTime:  0.01
            );
            sig = Decimator.ar(sig, samps, 31);                     //sample rate reduction
            sig = (sig / (lim*2)) + (lim/2);                        //range 0 - 1 for encoding
            sig = (
                1 + ((u * sig) - ((u * sig) % (1 + (u - u.floor)))) //u-law encoding (bitcrush pt 1)
            ).log/(1 + u).log;
            sig = (1/u) * ((1+u).pow(sig) - 1);                     //u-law decoding (bitcrush pt 2)
            sig = (sig - (lim/2)) * (lim*2);                        //revert range after decoding
            sig = Slew.ar(sig, r, r);                               //filter out some rough edges
            sig = XFade2.ar(sig,
                Shaper.ar(tfBuf, sig), (\drive.kr(0.05)*2) - 1      //waveshaper drive (using the tf wavetable)
            );

            Out.ar(\outbus.kr(0), XFade2.ar(in, sig, (\drywet.kr(1)*2) - 1)); //drywet out
        });

        var batchformat = '';

        //ulaw synth & bus
        fxBus = Bus.audio(context.server, 2);
        fx = fxDef.play(args: [\inbus, fxBus]);


        //gator is a multi-voice control router/voice allocator - we send it info to create synths for fmDef
        gator = OrgnGator.new(context.server, fmDef, [\outbus, fxBus], fx, \addBefore);

        //gator can make most of the engine commands automatically - we send a list of *exclusions* for the conrols we're adding manually
        gator.addCommands(this, [\hz, \mod0, \mod1, \mod2, \outbus, \done, \release]);
        
        //then we add the controls manually - in these cases there's a bit of interpretation needed before sending values to gator
        
        //set hz instantaneously
        this.addCommand(\hz, \sf, { arg msg;
            var id = msg[1], hz = msg[2];
            gator.set(\hz, id, hz, hz, 0);
        });

        //set hz XLine arguments (start, end, dur)
        this.addCommand(\glide, \sfff, { arg msg;
            msg.removeAt(0);
            gator.set(\hz, *msg);
        });

        //start a note with pitch/velocity
        this.addCommand(\noteOn, \sff, { arg msg;
            var id = msg[1], hz = msg[2], vel = msg[3];
            gator.set(\velocity, id, vel);
            gator.set(\hz, id, hz, hz, 0);
            gator.set(\gate, id, 1);
        });

        //start a note gliding
        this.addCommand(\noteGlide, \sffff, { arg msg;
            var id = msg[1], start = msg[2], end = msg[3], dur = msg[4], vel = msg[5];
            gator.set(\vel, id, vel);
            gator.set(\hz, id, start, end, dur);
            gator.set(\gate, id, 1);
        });

        //end a note (shortcut for gate(0))
        this.addCommand(\noteOff, \s, { arg msg;
            gator.set(\gate, msg[1], 0);
        });

        //combine mod0/mod1/mod2 into a single mod command
        this.addCommand(\mod, \siif, { arg msg;
            var id = msg[1], modulator = msg[2], carrier = msg[3], amt = msg[4];
            gator.setAt((\mod ++ (carrier - 1)).asSymbol, id, modulator, amt);
        });
        
        //the release command must manually set the doneAction for each operator via \done
        this.addCommand(\release, \sif, { arg msg;
            var id = msg[1], rel, done, max, idx;
            gator.setAt(\release, *msg);
            rel = gator.get(\release, \all);
            done = 0!(rel.size);
            max = 0; idx = 0;
            rel.do({ arg v, i; if(v > max, { max = v; idx = i; }) });
            done[idx] = 1;
            gator.set(\done, \all, *done);
        });

        ops.do({
            batchformat = batchformat ++ \f;
        });

        //set a control for all operators in one go
        this.addCommand(\batch, \ss ++ batchformat, { arg msg;
            msg[1] = msg[1].asSymbol;
            msg.removeAt(0);
            gator.set(*msg);
        });
        
        //auto-add fx commands 
        fxDef.allControlNames.do({ arg c;
            var n = c.name;
            if((n != \inbus) && (n != \outbus), { this.addCommand(n, \f, { arg msg; fx.set(n, msg[1]) })});
        });
        
        //print synth default values - will be useful when I'm making the controlspecs
        "synth control defaults:".postln;
        (fmDef.allControlNames ++ fxDef.allControlNames).do({ arg c;
            [c.name, c.defaultValue].postln;
        });
	}

	free {
	
	    //free gator, fx synth, fx bus
	    gator.free;
		fx.free;
		fxBus.free;
	}
}
