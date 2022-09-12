/**
 *  Button Keypad Single Active Button
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
    name: "Bathroom fan Timer",
    namespace: "nektarios",
    author: "Nektarios",
    description: "Timer for Bathroom fan.",
    category: "Lights",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/ModeMagic/rise-and-shine.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/ModeMagic/rise-and-shine@2x.png"
)

preferences {
    page(name: "pageMainPage", content: "pageMainPage", title: "Setup Switches", uninstall: true, install: true, nextPage: null)
    
}

def pageMainPage() {
    dynamicPage(name: "pageMainPage") {        
        section("Fan to control") {
            input "fanSwitch", "capability.switch", title:"Select Fan", multiple:false, required:true
        }
        section("Switch that is enabled when Fan should be auto-enabled via a humidity sensor (should be set via Auto Humidity Vent app).") {
            input "fanAuto", "capability.switch", title:"Select Automatic Fan Virtual Switch", multiple:false, required:false
        }
        section("Keypad Parent Device") {
            input "fanKeypad", "capability.actuator", title:"Select Fan", multiple:false, required:true
        }
        section(){
            (1..5).each() { n ->
                section("Switch ${n}") {
                    input "switch${n}", "capability.switch", title:"Select switch", multiple:false, required:true
                    input "switchTime${n}", "NUMBER", title:"Time delay (minutes) for switch when pressed", required:true
                }
            }
        }
        section("Logging") {
	    	input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true	
	    }
    }
}

def installed() {
	log.debug "Installed with settings: ${settings}"
	initialize()
}

def updated() {
	log.debug "Updated with settings: ${settings}"
	unsubscribe()
	initialize()
}
    
def log(message) {
    if (logEnable) { log.debug message }
}

def initialize() {
    log.warn "debug logging is: ${logEnable == true}"
	if (logEnable) runIn(1800,logsOff)
    atomicState.clear()
    atomicState.fullFanTime = 10
    atomicState.timeRemaining =  10
    atomicState.pressedSwitch = 1
    atomicState.updateLed = null
    atomicState.turningOffOldLed = null
    atomicState.switchingFan = null
    atomicState.TIME_TO_IGNORE = 500
    
    subscribeToEvents()
}

def subscribeToEvents() {
    subscribeSwitches()
    subscribeFan()
}

def subscribeSwitches() {
    def lastSwitch=null
    (1..5).each() { n ->
        def s = getSwitch(n)
        subscribe(s, "switch.on", switchOnHandler)
        subscribe(s, "switch.off", switchOffHandler)
        lastSwitch=s
    }
    //push state to last swith (this should trigger it for all)
    //if (lastSwitch.hasCommand("pushStateToKeypad")) {
    //   lastSwitch.pushStateToKeypad()
    //}
    fanKeypad.syncVirtualStateToIndicators()
}

/*def unsubscribeSwitches() {
    (1..5).each() { n ->
        unsubscribe(getSwitch(n))
    }
}*/

def subscribeFan() {
    subscribe(fanSwitch, "switch.on", fanOnHandler)
    subscribe(fanSwitch, "switch.off", fanOffHandler) 
    subscribe(fanAuto, "switch.on", fanAutoOnHandler)
    subscribe(fanAuto, "switch.off", fanAutoOffHandler)  
}

/*
    Keypad displays current time left.
        If time left == 0 then, set it to atomicState.fullFanTime
    
    
*/

def manageFanAndLed() {
    if (!atomicState.endTime) return
     
    //compute new time remaining
    atomicState.timeRemaining = atomicState.endTime - now()
    
    log("Time remaining min:${atomicState.timeRemaining/60/1000}")

    
    if (atomicState.timeRemaining > 0) {        
        def switchToSet = -1
        def switchToSetTimeDiff = 9999

        //figure out which LED to illuminate
        /*
         11
    5  10  30  60
        in the above case where remaining time = 11, find 30
        */

        //see if there is another switch less than the time remaining to set
        //loop through all the switches. Pick the one that is >= time remaining, but minimizes the difference between time remaining and that
        def timeRemainingMinutes = atomicState.timeRemaining/60/1000
        (1..5).each() { n ->
            def switchTime = getSwitchTime(n)
            
            //ignore the -1 switch (infinite hold)
            if (switchTime > 0) {
                def timeDiff = switchTime - timeRemainingMinutes
                if (timeDiff <= switchToSetTimeDiff && timeDiff >= 0) {
                    switchToSet = n
                    switchToSetTimeDiff = timeDiff
                }
            }
        }
        
        //If fan was auto-turned on during the on period, then don't update the LEDs
        if (fanAuto && fanAuto.latestValue("switch") == "on") {
            log("Fan was automatically enabled. Ignoring the time remaining.")
        }
        else {
            //turn on the switch that was found above (if any)
            setSwitchLedOnOthersOff(switchToSet)
        }
        
        /*
         11
    5  10  30  60
        in the above case where remaining time = 11, find 10
        */
        
        //determine time until next LED change
        def nextSwitchToSet = -1
        def nextSwitchToSetTimeDiff = -9999 //note: using large negative this time since looking fro closes time _smaller_ than current
        //look for next smallest time LED after the current one
        timeRemainingMinutes = atomicState.timeRemaining/60/1000
        (1..5).each() { n ->
            def switchTime = getSwitchTime(n)
            
            //ignore the -1 switch (infinite hold)
            if (switchTime > 0) {
                def timeDiff = switchTime - timeRemainingMinutes
                if (timeDiff >= nextSwitchToSetTimeDiff && timeDiff < 0) {
                    nextSwitchToSet = n
                    nextSwitchToSetTimeDiff = timeDiff
                }
            }
        }
        //log("current switch to set: $switchToSet")
        //log("next switch to set: $nextSwitchToSet")
        //log("time left ${atomicState.timeRemaining/1000} sec")
        
        //if there is a smaller switch, find difference between two switches and set timer to that time
        if (nextSwitchToSet > 0) {
            def timeDiff = (atomicState.timeRemaining - (getSwitchTime(nextSwitchToSet)*60*1000)).toInteger()
            runInMillis(timeDiff, "manageFanAndLed")
            runInMillis(atomicState.timeRemaining, "finalCheck")
        }
        else {
            runInMillis(atomicState.timeRemaining, "manageFanAndLed")
        }    
    }
    else {
        if (fanAuto && fanAuto.latestValue("switch") == "on") {
            log("Time is up, but since fan was auto-turned on, not turning fan off")
        }
        else {
            log("turn fan OFF")
            fanOff()
        }
    }
}

def finalCheck() {
    manageFanAndLed()
}

def switchOnHandler(evt) {
    def evtName = evt.name
    def evtValue = evt.value
    def evtDeviceId = evt.deviceId
    
    if (atomicState.updateLed && (now()-atomicState.updateLed) < atomicState.TIME_TO_IGNORE) {
        log("Ignoring switchOnHandler HANDLER $evt.device.label $evtName  $evtValue  $evtName")
        return
    }
    else {
        log("switchOnHandler HANDLER $evt.device.label $evtName  $evtValue  $evtName")
    }
    
    
    if (atomicState.lastSwitchDeviceId && atomicState.lastSwitchDeviceId != evtDeviceId) {
        log("turn off last one: $atomicState.lastSwitchNumber")
        atomicState.turningOffOldLed = now()
        getSwitch(atomicState.lastSwitchNumber).off()
        fanKeypad.syncVirtualStateToIndicators()
    }
       
    fanOn()
    unschedule()
    fanOn() //ensure fan is on (incase it was turned off by previous schedule)
    
    //unsubscribeSwitches()
    def switchNumber = -1
    //get the switch number, then the switch time. Then set the time remaining to that and sync the LEDs
    for ( i in 1..5) {
        def s = getSwitch(i)
        if ("$s.id" == "$evtDeviceId") {
            switchNumber = i
        }
        else if (s.latestValue("switch") == "on") {
            //turn other switches off immediatly
            atomicState.turningOffOldLed = now()
            
            //log("THE NUMBER $i")
            //log("THE SWTICH $s")
            //if (s.hasCommand("pushStateToKeypad")) {
            //    s.pushStateToKeypad()
            //}
            s.off()
            fanKeypad.syncVirtualStateToIndicators()
        }
    }
    //atomicState.updateLed = now()
    //runInMillis(50, "subscribeSwitches")

    atomicState.pressedSwitch = switchNumber
    def switchTime =  getSwitchTime(switchNumber)
    fanOnFor(switchTime)
    
    atomicState.lastSwitchNumber = switchNumber
    atomicState.lastSwitchDeviceId = evtDeviceId
    
    
    ///if (state.prevOn) { state.prevOn.off() }
    /*for (s in switches) {
        if ("$s.id" == "$evtDeviceId") {
            log ("turn off $s.label")
            s.off()
            if (s.hasCommand("pushStateToKeypad")) {
                s.pushStateToKeypad()
            }
        }
    }*/
}

def fanOnFor(switchTime) {
    if (!switchTime || switchTime<=0) return
    atomicState.fullFanTime = switchTime
    atomicState.timeRemaining = (atomicState.fullFanTime * 60 * 1000).toInteger()
    atomicState.endTime = now() + (atomicState.timeRemaining)        
    //log("Current Time:  ${new Date().format("HH:mm:ss", location.timeZone)}")
    log("End Time: ${new Date(atomicState.endTime).format("HH:mm:ss", location.timeZone)}")
    unschedule()
    manageFanAndLed()
}

def switchOffHandler(evt) {
    def evtName = evt.name
    def evtValue = evt.value
    def evtDeviceId = evt.deviceId
    //log("switchOffHandler HANDLER $evt.device.label $evtName  $evtValue  $evtName")
    
    if ((atomicState.updateLed && (now()-atomicState.updateLed) < atomicState.TIME_TO_IGNORE) ||
        (atomicState.turningOffOldLed && (now()-atomicState.turningOffOldLed) < atomicState.TIME_TO_IGNORE*2)) {
        log("Ignoring switchOffHandler HANDLER $evt.device.label $evtName  $evtValue  $evtName")
        return
    }
    else {
        log("switchOffHandler HANDLER $evt.device.label $evtName  $evtValue  $evtName")
    }
    
    def switchNumber = -1
    //def anyOn = false
    //get the switch number, then the switch time. Then set the time remaining to that and sync the LEDs
    for ( i in 1..5) {
        def s = getSwitch(i)
        if ("$s.id" == "$evtDeviceId") {
            switchNumber = i
            break
        }
        /*if (s.latestValue("switch") == "on") {
            anyOn = true
        }*/
    }
    //if (!anyOn) { //try forcing on
        //if fan is on, then turn it off
        if (fanSwitch.latestValue("switch")=="on") {
            fanOff() //turning it off will turn back on the appropriate LED
        }
        else {
            //for faster response, immediatly turn the fan on
            atomicState.switchingFan = now()
            fanSwitch.on()
            atomicState.switchingFan = now()            
            //otherwise, turn the switch back on in order to turn the fan on
            getSwitch(switchNumber).on()
        }
    //}
}

//set switch LED indicated in the parameter ON, leave the rest off
def setSwitchLedOnOthersOff(switchToSet) {
    if (!switchToSet) return
    
    //atomicState.updateLed = true    
    //unsubscribeSwitches()
    //first turn off any switch that is not switchToSet
    (1..5).each() { n ->
        if (n != switchToSet) {
            def sOff = getSwitch(n)
            if (sOff.latestValue("switch") == "on") {
                atomicState.switchingFan = now()
                atomicState.updateLed = now()
                sOff.off()
                //already triggering this below, so commenting out here
                /*if (sOff.hasCommand("pushStateToKeypad")) {
                    sOff.pushStateToKeypad()
                }*/
                fanKeypad.syncVirtualStateToIndicators()
             }
        }
    }
    
    if (switchToSet<=0) {
        return

    }
    
    //turn on switchToSet
    def s = getSwitch(switchToSet)
    if (s.latestValue("switch") == "off") {
        atomicState.switchingFan = now()
        atomicState.updateLed = now()
        s.on()
        //if (s.hasCommand("pushStateToKeypad")) {
        //    log("pushing state to keypad")
        //    s.pushStateToKeypad()
        //}
        fanKeypad.syncVirtualStateToIndicators()
    }
    atomicState.switchingFan = now()
    atomicState.updateLed = now()
    //runInMillis(50, "subscribeSwitches")
    //atomicState.updateLed = false
}

def fanAutoOnHandler(evt) {
    def evtName = evt.name
    def evtValue = evt.value
    def evtDeviceId = evt.deviceId
    fanOn()
    atomicState.updateLed = now() + 3000
    (1..5).each() { n ->
        def s = getSwitch(n)
        s.on()
    }
}

def fanAutoOffHandler(evt) {
    def evtName = evt.name
    def evtValue = evt.value
    def evtDeviceId = evt.deviceId
    atomicState.updateLed = now() + 3000
    (1..5).each() { n ->
        def s = getSwitch(n)
        s.off()
    }
    fanOff()
}

def fanOnHandler(evt) {
    def evtName = evt.name
    def evtValue = evt.value
    def evtDeviceId = evt.deviceId
    if ((atomicState.switchingFan && (now()-atomicState.switchingFan) < atomicState.TIME_TO_IGNORE) || (fanAuto && fanAuto.latestValue("switch") == "on")) {
        log("Ignoring fanOnHandler HANDLER $evt.device.label $evtName  $evtValue  $evtName")
        return
    }
    else {
        log("fanOnHandler HANDLER $evt.device.label $evtName  $evtValue  $evtName")
    }
    
    //keep the fan on for the last on time, if none, default to 10
    log("Fan turned on manually, turn on for previous time ${atomicState.fullFanTime}")

    fanOnFor(atomicState.fullFanTime)
}

def fanOffHandler(evt) {
    def evtName = evt.name
    def evtValue = evt.value
    def evtDeviceId = evt.deviceId
    //ignore if did a fan switch in past 500ms
    if ((atomicState.switchingFan && (now()-atomicState.switchingFan) < atomicState.TIME_TO_IGNORE) || (fanAuto && fanAuto.latestValue("switch") == "on")) {
        log("Ignoring fanOffHandler HANDLER $evt.device.label $evtName  $evtValue  $evtName")
        return
    }
    else {
        log("fanOffHandler HANDLER $evt.device.label $evtName  $evtValue  $evtName")
    }
    
    //turn pressed switch back on
    //setSwitchLedOnOthersOff(atomicState.pressedSwitch)
    setSwitchLedOnOthersOff(-1)
    log("Fan turned off, set LED back to switch ${atomicState.pressedSwitch}")
}

//turn the fan on
def fanOn() {
    atomicState.switchingFan = now()
    //unsubscribe(fanSwitch)
    fanSwitch.on()
    atomicState.switchingFan = now()
    //atomicState.switchingFan = null
    //runInMillis(50, "subscribeFan")

}

//turn the fan off and set the LED back to previous
def fanOff() {
    //atomicState.switchingFan = true
    //unsubscribe(fanSwitch)
    atomicState.switchingFan = now()
    unschedule()
    log("turning off fan...")
    fanSwitch.off()
    atomicState.switchingFan = now()
    //atomicState.switchingFan = false
    //runInMillis(300, "subscribeFan")
    subscribeFan()
    
    //turn pressed switch back on
    //setSwitchLedOnOthersOff(atomicState.pressedSwitch)
    setSwitchLedOnOthersOff(-1)
    log("Fan turned off, set LED back to switch ${atomicState.pressedSwitch}")
}



def getSwitch(num){
    return settings."switch${num}"
    /*
   switch(num) { 
       case 1: return switch1
       case 2: return switch2
       case 3: return switch3
       case 4: return switch4
       case 5: return switch5
    }
    return null*/
}

def getSwitchTime(num){
    return settings."switchTime${num}"
    /*
   def switchTime = -1
   switch(num) { 
       case 1: 
           switchTime = switchTime1 
           break
       case 2: 
           switchTime = switchTime2 
           break
       case 3: 
           switchTime = switchTime3 
           break
       case 4: 
           switchTime = switchTime4 
           break
       case 5: 
           switchTime = switchTime5 
           break
    }
    
    //DEBUG only - switch time to seconds
    //if (switchTime > 0) {
    //    switchTime = switchTime / 60
    //}
    return switchTime*/
}

def logsOff(){
	log.warn "debug logging disabled..."
	logEnable = false
}

  