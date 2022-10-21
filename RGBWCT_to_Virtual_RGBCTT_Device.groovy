/**
 *  RGBWCT to Virtual RGBW Device
 *
 *  Copyright 2020 Nektarios
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
definition(
    name: "RGBWCT to Virtual RGBCTT Device",
    namespace: "nektarios",
    author: "Nektarios",
    description: "Make a two-channel RGB + W (CCT) work like a RGBW Light Device",
    category: "Lights",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/ModeMagic/rise-and-shine.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/ModeMagic/rise-and-shine@2x.png"
)


preferences {
    page(name: "pageMainPage", content: "pageMainPage", title: "Setup vBulb", uninstall: true, install: true, nextPage: null)
    
}

def pageMainPage() {
    dynamicPage(name: "pageMainPage") {
        section("RGB LEd Device") {
           input "gRgb", "capability.colorControl", title: "RGB device to control",  multiple: false, required: true
        }
        section("White Tunable Device") {
           input "gW", "capability.switchLevel", title: "White Channel device to control",  multiple: false, required: true
        }
        section("Group Device") {
           input "gGroup", "capability.switchLevel", title: "Group device that controls both channels (used for on/off)",  multiple: false, required: true
        }
        section("Virtual RGBW Device") {
           input "vBulb", "capability.colorControl", title: "Virtual RGBW device. Changes here are applied to the gledopodo device.",  multiple: false, required: true
        }

        section("Match this color temp when turned on (optional)") {
           input "ctBulb", "capability.colorTemperature", title: "Color temp bulb to match.",  multiple: false, required: false
            List vars = []
            getAllGlobalVars().each{vars += it.key}
            input "ctBulbVar", "enum", title: "Or, Variable to match", options: vars

        }
        
        section("Customize white balance?") {
           input "customWB", "bool", title: "Customize with values below?",  default:false, required: true
        }
        
        section("Level") {
            input "wLevel", "number", title:"White level as percentage of rgb", defaultValue:100
            input "rgbLevel", "number", title:"RGB level as percentage of white", defaultValue:100
        }
        section("Time") {
            input "syncTime", "number", title:"Check every n minutes & sync Virtual RGBW device with group device", defaultValue:100
        }
        
        section("***** CT<=2800 *****") {
            input "wCT2800", "number", title:"White Color Temp when CT<=2800 (0 to ignore)", defaultValue:2200
            input "rgbCT2800", "number", title:"RGB Color Temp when CT<=2800", defaultValue:2500
        }
        section("***** CT<=3100 *****") {
            input "wCT3100", "number", title:"White Color Temp when CT<=3100 (0 to ignore)", defaultValue:2500
            input "rgbCT3100", "number", title:"RGB Color Temp when CT<=3100", defaultValue:2500
        }
        section("***** CT<=4100 *****") {
            input "wCT4100", "number", title:"White Color Temp when CT<=4100 (0 to ignore)", defaultValue:3300
            input "rgbCT4100", "number", title:"RGB Color Temp when CT<=4100", defaultValue:2500
        }
        section("***** CT<=5100 *****") {
            input "wCT5100", "number", title:"White Color Temp when CT<=5100 (0 to ignore)", defaultValue:3500
            input "rgbCT5100", "number", title:"RGB Color Temp when CT<=5100", defaultValue:2600
        }
        section("***** CT<=6100 *****") {
            input "wCT6100", "number", title:"White Color Temp when CT<=6100 (0 to ignore)", defaultValue:4500
            input "rgbCT6100", "number", title:"RGB Color Temp when CT<=6100", defaultValue:2800
        }

        section("Logging") {
		    input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true	
	    }    
        section {
            label(title: "Label This SmartApp", required: false, defaultValue: "", description: "Highly recommended", submitOnChange: true)
        }
    }
}
    
def installed() {
	log.debug "Installed with settings: ${settings}"
	initialize()
}

def updated() {
	log.debug "Updated with settings: ${settings}"
    state.clear()
	unsubscribe()
	initialize()
    
    log.debug(getRgbFromTemperature(3100))
}
    
def log(message) {
    if (logEnable) { log.debug message }
}

def initialize() {
    log.warn "debug logging is: ${logEnable == true}"
	//if (logEnable) runIn(1800,logsOff)
        
    //do not clear atomic stae or whatever gets set below will be cleared
    state.clear()
    
    state.ignoreBulbEventTime=0
    state.ignoreBulbEventCount=0
    state.ignoreGroupEventTime=0
    state.ignoreGroupEventCount=0
    state.TIME_TO_IGNORE = 5000
    state.lastScheduledSyncTime = now()
    
    if (wLevel <= 0) {
        wLevel = 1
    }
    if (wLevel > 100) {
        wLevel = 100
    }
    if (rgbLevel <= 0) {
        rgbLevel = 1
    }
    if (rgbLevel > 100) {
        rgbLevel = 100
    }

    subscribeToEvents()
    
    if (syncTime != 0) { 
        runIn(60*syncTime, scheduledSync)
    }
}



def subscribeToEvents() {
    subscribe(vBulb, "switch", bulbSwitchHandler, [filterEvents:false])
    subscribe(vBulb, "level", bulbLevelChangedHandler, [filterEvents:false])
    subscribe(vBulb, "colorTemperature", bulbColorTempChangedHandler, [filterEvents:false])    
    subscribe(vBulb, "rgb", bulbColorChangedHandler, [filterEvents:true])
    subscribe(vBulb, "saturation", bulbColorChangedHandler, [filterEvents:true])
    subscribe(vBulb, "hue", bulbColorChangedHandler, [filterEvents:true])

    
    subscribe(gGroup, "switch", syncGroupBulbSwitch, [filterEvents:false])
    subscribe(gGroup, "level", syncGroupBulbLevel, [filterEvents:false]) 
    subscribe(gGroup, "colorTemperature", syncGroupColorTemp, [filterEvents:true])    
    subscribe(gGroup, "rgb", syncGroupColor, [filterEvents:true])
    subscribe(gGroup, "saturation", syncGroupColor, [filterEvents:true])
    subscribe(gGroup, "hue", syncGroupColor, [filterEvents:true])

}

def scheduledSync() {
    state.lastScheduledSyncTime = now()
    
    def val = gGroup.latestValue("switch")
    if (val=="on") {
        if (vBulb.latestValue("switch")!="on"){
            log.debug "Group was unexpectedly on. Turning on virtual light also."
            vBulb.on()
        }
    }
    else {
        if (vBulb.latestValue("switch")!="off"){
            log.debug "Group was unexpectedly off. Turning off virtual light also."
            vBulb.off()
        }
    }
    
    runIn((60*syncTime), scheduledSync)    
}


def syncGroupBulbSwitch(evt) {
    if (state.ignoreGroupEventTime && state.ignoreGroupEventCount && (now()-state.ignoreGroupEventTime) < state.TIME_TO_IGNORE && state.ignoreGroupEventCount > 0) {
        state.ignoreGroupEventCount -= 1
        if (state.ignoreGroupEventCount <= 0) {
            state.ignoreGroupEventCount= 0
        }
        log("ignoring syncGroupBulbSwitch $evt.value")
        return
    }
    
    log("syncGroupBulbSwitch $evt.value")
    
    if (evt.value=="on") {
        if (vBulb.latestValue("switch")!="on"){
   
            vBulb.on()
        }
 
        return ///***************
        if (ctBulb) {
            //setCT(ctBulb.latestValue("colorTemperature").toInteger())
            vBulb.setColorTemperature(gGroup.latestValue("colorTemperature").toInteger())
            vBulb.setLevel(gGroup.latestValue("level").toInteger())
            /*
            if (!(wLevel || rgbLevel) || (wLevel == 100 && rgbLevel == 100)){
                
            }
            else {
                vBulb.setLevel(wLevel+rgbLevel/2)
            }*/
            /*else {
                if (wLevel != 100) {
                    gW.setLevel(gRgb.latestValue("level").toInteger()*wLevel/100)
                }
                else if (rgbLevel != 100) {
                    gRgb.setLevel(gW.latestValue("level").toInteger()*rgbLevel/100)
                }
            }*/
        }
    }
    else {
        if (vBulb.latestValue("switch")!="off"){
            vBulb.off()
        }
        //state.ignoreBulbEventTime =now()
        //if (!state.ignoreBulbEventCount || state.ignoreBulbEventCount>10) {state.ignoreBulbEventCount = 2} else {state.ignoreBulbEventCount += 2}
    
        //vBulb.off()
    }
}

def syncGroupBulbLevel(evt) {
    if (state.ignoreGroupEventTime && state.ignoreGroupEventCount && (now()-state.ignoreGroupEventTime) < state.TIME_TO_IGNORE && state.ignoreGroupEventCount > 0) {
        state.ignoreGroupEventCount -= 1
        log("ignoring syncGroupBulbLevel $evt.value")
        if (state.ignoreGroupEventCount <= 0) {
            state.ignoreGroupEventCount= 0
        }
        return
    }
    state.ignoreBulbEventTime =now()
    if (!state.ignoreBulbEventCount || state.ignoreBulbEventCount>10) {state.ignoreBulbEventCount = 2} else {state.ignoreBulbEventCount += 2}
    log("syncGroupBulbLevel $evt.value")
    
    vBulb.setLevel(evt.value.toInteger())
}

def syncGroupColor(evt) {
    if (state.ignoreGroupEventTime && state.ignoreGroupEventCount && (now()-state.ignoreGroupEventTime) < state.TIME_TO_IGNORE && state.ignoreGroupEventCount > 0) {
        state.ignoreGroupEventCount -= 1
        log("ignoring syncGroupColor $evt.value")
        if (state.ignoreGroupEventCount <= 0) {
            state.ignoreGroupEventCount= 0
        }
        return
    }
    //both virtual bulb and group even events need to be ignored as they will both send events. The group will send events because of the new color.
    state.ignoreBulbEventTime =now()
    if (!state.ignoreBulbEventCount || state.ignoreBulbEventCount>10) {state.ignoreBulbEventCount = 2} else {state.ignoreBulbEventCount += 2}
    //state.ignoreGroupEventTime =now()
    //if (!state.ignoreGroupEventCount || state.ignoreGroupEventCount>10) {state.ignoreGroupEventCount = 2} else {state.ignoreGroupEventCount += 2}

    log("syncGroupColor $evt.value")
    
    def map = [:]
    map."hue"=gGroup.latestValue("hue").toInteger()
    map."saturation"=gGroup.latestValue("saturation").toInteger()
    map."level"=gGroup.latestValue("level").toInteger()
    log ("set color to $map")
    vBulb.setColor(map)
}

def syncGroupColorTemp(evt) {
    if (state.ignoreGroupEventTime && state.ignoreGroupEventCount && (now()-state.ignoreGroupEventTime) < state.TIME_TO_IGNORE && state.ignoreGroupEventCount > 0) {
        state.ignoreGroupEventCount -= 1
        if (state.ignoreGroupEventCount <= 0) {
            state.ignoreGroupEventCount= 0
        }
        log("ignoring syncGroupColorTemp $evt.value")
        return
    }
    //both virtual bulb and group even events need to be ignored as they will both send events. The group will send events because of the new color.
    state.ignoreBulbEventTime =now()
    if (!state.ignoreBulbEventCount || state.ignoreBulbEventCount>10) {state.ignoreBulbEventCount = 2} else {state.ignoreBulbEventCount += 2}
    //state.ignoreGroupEventTime =now()
    //if (!state.ignoreGroupEventCount || state.ignoreGroupEventCount>10) {state.ignoreGroupEventCount = 2} else {state.ignoreGroupEventCount += 2}

    
    log("syncGroupColorTemp $evt.value")
    
    vBulb.setColorTemperature(gGroup.latestValue("colorTemperature").toInteger())
    //this causes loops!! //commented 1/30/2022
    //setCT(evt.value.toInteger())
}

def bulbSwitchHandler(evt) {
    if (state.ignoreBulbEventTime && state.ignoreBulbEventCount && (now()-state.ignoreBulbEventTime) < state.TIME_TO_IGNORE && state.ignoreBulbEventCount > 0) {
        state.ignoreBulbEventCount -= 1
        log("ignoring bulbSwitchHandler $evt.value")
        if (state.ignoreBulbEventCount <= 0) {
            state.ignoreBulbEventCount= 0
        }
        return
    }
    state.ignoreGroupEventTime =now()
    if (!state.ignoreGroupEventCount || state.ignoreGroupEventCount>10) {state.ignoreGroupEventCount = 2} else {state.ignoreGroupEventCount += 2}
    
    if (state.lastScheduledSyncTime > 1000*60*syncTime*2) {
        runIn(60*syncTime, scheduledSync)    
    }


    log("bulbSwitchHandler $evt.value")
    
    if (evt.value=="on") {
        log("switch on")
        /*if (vBulb.latestValue("colorMode") == "CT") {
            setCT(vBulb.latestValue("colorTemperature"))
        }
        else {
            setCol(vBulb.latestValue("hue"),vBulb.latestValue("saturation"),vBulb.latestValue("level"))
            //gRgb.setLevel(100)
        }*/
        if (gGroup.latestValue("switch")!="on"){
   
            gGroup.on()
        }
    
        if (ctBulb || ctBulbVar) {
            def ct = 0
            if (ctBulb) {
                ct = ctBulb.latestValue("colorTemperature").toInteger()
            }
            else if (ctBulbVar) {
                ct = getGlobalVar(ctBulbVar).value
            }
            pauseExecution(100)
            setCT(ct)
            pauseExecution(100)
            setBulbLevel(100)
            state.bulbCT = ct
            state.bulbLevel =100
        }
        else {
            //gGroup.on()
        }
        runIn(1500,reapplyBulbSettings)
    }
    else {
        log("switch off")
        //gRgb.off()
        //pauseExecution(1400)
        //gW.off()
        gGroup.off()
    }
}

//Set bulb level and accomodate any percent change
def setBulbLevel(level) {
    if (!(wLevel || rgbLevel) || (wLevel == 100 && rgbLevel == 100)){
        gGroup.setLevel(level)
    }
    else {
        if (wLevel != 100) {
            gW.setLevel(level*wLevel/100)
            pauseExecution(100)
            gRgb.setLevel(level)
        }
        else if (rgbLevel != 100) {
            gRgb.setLevel(level*rgbLevel/100)
            pauseExecution(100)
            gW.setLevel(level)
        }
    }
    //runIn(1500,reapplyBulbSettings)
}

def bulbLevelChangedHandler(evt) {
    if (state.ignoreBulbEventTime && state.ignoreBulbEventCount && (now()-state.ignoreBulbEventTime) < state.TIME_TO_IGNORE && state.ignoreBulbEventCount > 0) {
        log("ignoring bulbLevelChangedHandler $evt.value")
        state.ignoreBulbEventCount -= 1
        if (state.ignoreBulbEventCount <= 0) {
            state.ignoreBulbEventCount= 0
        }
        return
    }
    state.ignoreGroupEventTime =now()
    if (!state.ignoreGroupEventCount || state.ignoreGroupEventCount>10) {state.ignoreGroupEventCount = 2} else {state.ignoreGroupEventCount += 2}

    
    log("bulbLevelChangedHandler $evt.value")
    setBulbLevel(evt.value.toInteger())
    state.bulbLevel =evt.value.toInteger()
    runIn(1500,reapplyBulbSettings)
    //gGroup.setLevel(evt.value.toInteger())
    /*gW.setLevel(evt.value.toInteger())
    pauseExecution(150)
    gRgb.setLevel(evt.value.toInteger())*/

    /*if (vBulb.latestValue("colorMode") == "CT") {
        gW.setLevel(evt.value.toInteger())
    }
    else {
        gRgb.setLevel(evt.value.toInteger())
    }*/
}

def reapplyBulbSettings() {
   /* state.ignoreBulbEventTime =now()
    if (!state.ignoreBulbEventCount || state.ignoreBulbEventCount>10) {state.ignoreBulbEventCount = 2} else {state.ignoreBulbEventCount += 2}
    state.ignoreGroupEventTime =now()
    if (!state.ignoreGroupEventCount || state.ignoreGroupEventCount>10) {state.ignoreGroupEventCount = 2} else {state.ignoreGroupEventCount += 2}
    */
    if (state.bulbLevel) {
        setBulbLevel(state.bulbLevel)
        state.bulbLevel = null
        pauseExecitio(100)
    }
    if (state.bulbCT) {
        setCT(state.bulbCT)
        state.bulbCT = null
    }
}

def bulbColorTempChangedHandler(evt) {
    log("bulbColorTempChangedHandler $evt.value")
    
    if (state.ignoreBulbEventTime && state.ignoreBulbEventCount && (now()-state.ignoreBulbEventTime) < state.TIME_TO_IGNORE && state.ignoreBulbEventCount > 0) {
        log("ignoring bulbLevelChangedHandler $evt.value")
        state.ignoreBulbEventCount -= 1
        if (state.ignoreBulbEventCount <= 0) {
            state.ignoreBulbEventCount= 0
        }
        return
    }
    state.ignoreGroupEventTime =now()
    if (!state.ignoreGroupEventCount || state.ignoreGroupEventCount>10) {state.ignoreGroupEventCount = 2} else {state.ignoreGroupEventCount += 2}

    setCT(evt.value.toInteger())
    state.bulbCT = evt.value.toInteger()
    /*def ct = 0
    if (ctBulb) {
        ct = ctBulb.latestValue("colorTemperature").toInteger()
    }
    else if (ctBulbVar) {
        ct = getGlobalVar(ctBulbVar).value
    }
    state.bulbCT = ct
    */
}

def bulbColorChangedHandler(evt) {
    log("bulbColorChangedHandler $evt.value")
    
    if (state.ignoreBulbEventTime && state.ignoreBulbEventCount && (now()-state.ignoreBulbEventTime) < state.TIME_TO_IGNORE && state.ignoreBulbEventCount > 0) {
        log("ignoring bulbLevelChangedHandler $evt.value")
        state.ignoreBulbEventCount -= 1
        if (state.ignoreBulbEventCount <= 0) {
            state.ignoreBulbEventCount= 0
        }
        return
    }

    state.ignoreGroupEventTime =now()
    if (!state.ignoreGroupEventCount || state.ignoreGroupEventCount>10) {state.ignoreGroupEventCount = 2} else {state.ignoreGroupEventCount += 2}
    
    setCol(vBulb.latestValue("hue").toInteger(),vBulb.latestValue("saturation").toInteger(),vBulb.latestValue("level").toInteger())
}

def setCol(hue,saturation,level) {
    def map = [:]
    map."hue"=hue
    map."saturation"=saturation
    map."level"=level
    log ("set color to $map")
    gRgb.setColor(map)
    pauseExecution(150)
    gW.off()
}

def setCT(val) {
    def rgbCT = val
    def wCT = val
    if (customWB) {
        if (val <= 2800) {
            rgbCT = rgbCT2800
            wCT = wCT2800
        }
        else if (val <= 3100) {
            rgbCT = rgbCT3100
            wCT = wCT3100
        }
        else if (val <= 4100) {
            rgbCT = rgbCT4100
            wCT = wCT4100
        }
        else if (val <= 5100) {
            rgbCT = rgbCT5100
            wCT = wCT5100
        }
        else if (val <= 6500) {
            rgbCT = rgbCT6100
            wCT = wCT6100
        }
    }
    //log ("set color to $rgb and white to $wl")
    log ("set color to $rgbCT and white to $wCT")
    //setCol(hubitat.helper.ColorUtils.rgbToHSV( rgbVal ))
    //gGroup.setLevel(100)
    
    
    if (rgbCT==wCT) {
        gGroup.setColorTemperature(rgbCT)
    }
    else {
        gRgb.setColorTemperature(rgbCT)
        if (wCT>0) { 
            gW.setColorTemperature(wCT)
            pauseExecution(50)
        }
        gRgb.setColorTemperature(rgbCT)
        pauseExecution(50)
        if (wCT>0) { 
            gW.setColorTemperature(wCT)
            pauseExecution(50)
        }

    }
    //pauseExecution(1000)
    //gW.setLevel(wl)
}

def clamp(a, b, c) {
    if (a<b) return b
    if (a>c) return c
    return a
}

	/**
	 A temperature to color conversion, inspired from a blogpost from PhotoDemon
	 (http://www.tannerhelland.com/4435/convert-temperature-rgb-algorithm-code/).

	 @param temperature The temperature of an ideal black body, in Kelvins;
	 @param alpha       If true, the return value will be RGBA instead of RGB.
	 @return The corresponding RGB color.
	 */
	def getRgbFromTemperature(temperature, alpha=false)
	{
		// Temperature must fit between 1000 and 40000 degrees
        temperature = clamp(temperature, 1000, 4000)
        
        log.debug(temperature)

		// All calculations require tmpKelvin \ 100, so only do the conversion once
		temperature /= 100;

		// Compute each color in turn.
		int red, green, blue;
		// First: red
		if (temperature <= 66)
			red = 255;
		else
		{
			// Note: the R-squared value for this approximation is .988
			red = (int) (329.698727446 * (Math.pow(temperature - 60, -0.1332047592)));
			red = clamp(red, 0, 255);
		}

		// Second: green
		if (temperature <= 66)
			// Note: the R-squared value for this approximation is .996
			green = (int) (99.4708025861 * Math.log(temperature) - 161.1195681661);
		else
			// Note: the R-squared value for this approximation is .987
			green = (int) (288.1221695283 * (Math.pow(temperature - 60, -0.0755148492)));

		green = clamp(green, 0, 255);

		// Third: blue
		if (temperature >= 66)
			blue = 255;
		else if (temperature <= 19)
			blue = 0;
		else
		{
			// Note: the R-squared value for this approximation is .998
			blue = (int) (138.5177312231 * Math.log(temperature - 10) - 305.0447927307);

			blue = clamp(blue, 0, 255);
		}

		if (alpha)
			return [red, green, blue, 255]
		else
			return [red, green, blue]
	}

def logsOff(){
	log.warn "debug logging disabled..."
	logEnable = false
}

  