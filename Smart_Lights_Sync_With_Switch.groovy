/**
 *  Smart Lights Sync With Switch
 *
 *  Copyright 2020 Nektarios
 * 
 *  Changelog: 
 *  9/10 - Fixed issue with event value not being parsed as Int. Added Cache for some values.
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
    name: "Smart Lights Sync With Switch",
    namespace: "nektarios",
    author: "Nektarios",
    description: "Sync smart lights with Dimmer Switch",
    category: "Lights",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/ModeMagic/rise-and-shine.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/ModeMagic/rise-and-shine@2x.png"
)


preferences {
    page(name: "pageMainPage", content: "pageMainPage", title: "Setup Bulbs", uninstall: true, install: true, nextPage: null)
}


def pageMainPage() {
    dynamicPage(name: "pageMainPage") {
        section {
            label(title: "Label This SmartApp", required: false, defaultValue: "", description: "Highly recommended", submitOnChange: true)
        }
        section("Pause") {
            input "pauseApp", "bool", title: "Pause app",  multiple: false, required: true
        }
        section("Switch") {
            input "controlSwitch", "capability.pushableButton", title: "Dimmer that will control the state of the can lights",  multiple: false, required: true
        }
        section("Smart Bulbs") {
            input "bulbs", "capability.switchLevel", title: "Individual bulbs that will be controlled",  multiple: true, required: true
        }
        section("Bulb Group") {
            input "bulbGroup", "capability.switchLevel", title: "Group that controls the bulbs",  multiple: false, required: true
        }
        section("Color Temp Bulb") {
            input "colorTempBulb", "capability.colorTemperature", title: "Match color temp of this bult when turned on",  multiple: false, required: false
            List vars = []
            getAllGlobalVars().each{vars += it.key}
            input "colorTempBulbVar", "enum", title: "Or, Variable to match", options: vars
        }
        section("Logging") {
            input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true    
        }
        section("Button Event On/Off Values Type and Values") {
            input name: "onValue", type: "number", title: "Value for on button press", defaultValue: 7
            input name: "offValue", type: "number", title: "Value for off button press", defaultValue: 8
            input name: "onOffEvent", type:"enum", title: "Event type for on/off", options: ["pushed", "held"], description: "Select the type of button event sent for on/off.", defaultValue: "pushed", required: true
        }
        section("Button Event DIM Values Type and Values") {
            input name: "upValue", type: "number", title: "Value for button up hold", defaultValue: 5
            input name: "downValue", type: "number", title: "Value for button down hold", defaultValue: 6
            input name: "dimEvent", type:"enum", title: "Event type for dim", options: ["pushed", "held"], description: "Select the type of button event sent for START dim. For END dim it is required that a release event be sent", defaultValue: "pushed", required: true
            input name: "refreshAfterDim", type: "bool", title: "Refresh after dim? Some switches have issues with this - try to disable or enable if having issues after dimming.", defaultValue: true
        }
        section("Bulb Config") {
            input name: "minLevel", type: "number", title: "Minimum bulb level (1-99)", defaultValue: 5
            input name: "setBulbLevel", type: "bool", title: "Set bulb level when turned on?", description: "When cans are too dim that they won't turn on when sent an on command, set this to also set the minimum dim level.", defaultValue: false
        }
        section("Restrictions") {
            input "scenes", "capability.switch", title:"If any of these scenes are on, do not adjust the temperature/state of the bulb group (to allow a single or multiple bulbs to be indpenendly controlled)", multiple:true, required:false
        }
        section("MODE Settings") {
            input name: "numOfModes", type:"NUMBER", title: "Set mode-specific settings for the bulbs.",description: "0-4", range: "0..4", defaultValue: 0, required: true, submitOnChange: true, width:4
        }
        log.debug "numOfModes = $numOfModes"
        if (numOfModes >= 1) {
            section(){
                (1..numOfModes).each() { n ->
                    section("******************************Mode ${n} Settings**********************") {
                        input "mode${n}", "mode", title:"Mode ${n}", multiple:false, required:true
                        input "mode${n}BulbLevel", "number", title:"Level of bulbs for this mode (if 0, no level is forced in this mode)", defaultValue:0, required:false
                        input "mode${n}SetLevelOnlyOnce", "bool", title: "Set the mode level always (if off, then resets the mode only ONCE in 24 hrs - resets at midnight)?", defaultValue: true
                        input "mode${n}Bulbs", "capability.switchLevel", title: "Bulbs that will be ON in this mode (others will be turned off). If none selected, apply to ALL bulbs.",  multiple: true, required: false
                        input "mode${n}PressTwiceOn", "bool", title: "If user presses ON button press (${onValue}) when bulb is on, turn on ALL bulbs?", defaultValue: true
                        input "mode${n}PressTwiceOnLevel", "number", title: "If user presses ON button press (${onValue}) when bulb is on, turn on ALL bulbs at this level (if 0, no level is forced)", defaultValue: true
                    }   
                }
            }
        }

    }
}


def installed() {
    log.debug "Installed with settings: ${settings}"
    initialize()
}

def updated() {
    log.debug "Updated with settings: ${settings}"
    atomicState.clear()
    unsubscribe()
    
    
    
    atomicState.controlIsDimmableSwitch = controlSwitch.hasCommand("setLevel") //Check if control is a dimmable switch
    atomicState.controlIsSwitch = controlSwitch.hasCommand("on") //Check if control is a switch (otherwise it's just a remote)
    atomicState.expectedSwitch = "u" //undefined
    atomicState.expectedLevel = -1 //undefined
    atomicState.DEFAULT_TIME_TO_IGNORE = 1000
    atomicState.LONG_TIME_TO_IGNORE = 20000
    atomicState.TIME_TO_IGNORE = 1000
    atomicState.isModeSpecificState = true //if true, then handle only mode bulbs, otherwise handle all bulbs (used to handle the press twice on)
    atomicState.mode1BulbsSameGroup = -1
    atomicState.mode2BulbsSameGroup = -1
    atomicState.mode3BulbsSameGroup = -1
    atomicState.mode4BulbsSameGroup = -1
    atomicState.abortExpectedStateCheck = 0
    atomicState.lastMode = null
    atomicState.cache = null
    def setLevelOnlyOnce = [:]
    if (numOfModes) {
        setLevelOnlyOnce[numOfModes] = false
        schedule("0 0 0 * * ?",resetAtMidnight)
    }
    atomicState.setLevelOnlyOnce = setLevelOnlyOnce
    
    
    /*log ("bulbs " + getBulbs())
    log ("bulbs group " + getBulbGroup())
    log ("bulb group for mode 1" + getModeSetting(1,"BulbGroup"))*/
    
    cacheModeSettings()
    initialize()
}

def cacheModeSettings () {
    atomicState.cache = [:]
    def cache = [:] 
    //cache["getBulbs_Group"] = getBulbs(true)
    //cache["getBulbs_Defaut"] = getBulbs(false)
    cache["getModeNumber"] = getModeNumber().toInteger()
    cache["getIfPressTwiceOn"] = getIfPressTwiceOn()
    cache["getDeviceDifference_bulbs_getBulbs"] = getDeviceDifference(bulbs, getBulbs())
    atomicState.cache = cache
}

def locationModeChanged(evt) {
    cacheModeSettings()
}

def resetAtMidnight() {
    atomicState.setLevelOnlyOnce = [:]
}


def checkIfDevicesEqual(set1, set2) {
    def diff1 = set1.findAll{s1 -> !set2.find{ s2 -> s2.deviceId == s1.deviceId}}
    def diff2 = set2.findAll{s2 -> !set1.find{ s1 -> s2.deviceId == s1.deviceId}}
    return !diff1 && !diff2
}

//get diff devices, set1 - set2, ie. devices in set1 that are not in set 2
def getDeviceDifference(set1, set2) {
    return set1.findAll{s1 -> !set2.find{ s2 -> s2.deviceId == s1.deviceId}}
}


def getModeSetting(num, setting) {
    if (num >= 1 && num <= 4) {
        //if not in mode specific state, then return the standard bulb/group/level
        if (!atomicState.isModeSpecificState) {
            if (setting == "Bulbs") return bulbs
            if (setting == "BulbGroup") return bulbGroup
            if (setting == "BulbLevel") return settings."mode${num}PressTwiceOnLevel"
        }
        if (setting == "mode") return settings."mode${num}"
        if (setting == "BulbLevel") return settings."mode${num}BulbLevel"
        if (setting == "SetLevelOnlyOnce") return settings."mode${num}SetLevelOnlyOnce"
        if (setting == "Bulbs") {
            if (settings."mode${num}Bulbs") {
                return settings."mode${num}Bulbs"
            } else {
                return bulbs
            }
        }
        if (setting == "BulbGroup") {
            if (!settings."mode${num}Bulbs") return bulbGroup
            if (atomicState."mode${num}BulbsSameGroup" == -1) { //update number here
                //check if mode bulbs == the bulb group and vice-versa, if so, just use the bulb group (ASSUMPTION: the bulb group set is equal to the individual bulbs)
                if (checkIfDevicesEqual(bulbs, settings."mode${num}Bulbs")) { //update number here
                    atomicState."mode${num}BulbsSameGroup" = 1 //update number here
                }
                else {
                    atomicState."mode${num}BulbsSameGroup" = 0 //update number here
                }
            }
            if (atomicState."mode${num}BulbsSameGroup" == 1) { //update number here
                return bulbGroup
            }
            else  {
                return settings."mode${num}Bulbs" //update number here
            }
        }
        if (setting == "PressTwiceOn") return settings."mode${num}PressTwiceOn"
        if (setting == "PressTwiceOnLevel") return settings."mode${num}PressTwiceOnLevel"
    }

    log ("Incorrect parameters! $num, $setting")
}
    
def log(message, force=false) {
    if (logEnable || force==true) {
        log.debug message
        if (atomicState.lastLog && (now() - atomicState.lastLog) > 60*30*1000) {
            logEnable = false
        }
        atomicState.lastLog = now()
    }
}

def initialize() {
    log.warn "debug logging is: ${logEnable == true}"
    if (logEnable) runIn(1800,logsOff)
    
    if (!pauseApp) {
        subscribeToEvents()
    }
    else {
        log("App is paused.")
    }
}

def subscribeToEvents() {
    subscribeSwitches(controlSwitch)
    subscribeSwitches(bulbGroup)
    subscribe(controlSwitch, "pushed", buttonHandler)
    subscribe(controlSwitch, "held", buttonHandler)
    subscribe(controlSwitch, "released", buttonHandler)
    subscribe(location, "mode", locationModeChanged)
    if (colorTempBulb) {
        subscribe(bulbs, "switch", bulbHandler)
    }
    if (colorTempBulbVar) {
        subcribe(location, "variable:colorTempBulbVar.value", bulbHandler)
    }
}


//gets bulbs to operate on depending on the mode
def getBulbOnLevel() {
    if (!numOfModes || numOfModes == 0) {
        return 0
    }
    def modeNumber = getModeNumber()
    if (modeNumber > 0) {
        log("atomicState.setLevelOnlyOnce[modeNumber] ${atomicState.setLevelOnlyOnce[modeNumber]}")
        //this will get set if the mode-specific level was used and and SetLevelOnlyOnce setting is on
        if (atomicState.setLevelOnlyOnce[modeNumber]) {
            return 0
        }
        //remember current level (before mode change)
        if (!atomicState.levelBeforeModeLevel || atomicState.levelBeforeModeLevel==0) {
            atomicState.levelBeforeModeLevel = computeAvgBulbLevel() //bulbGroup.latestValue("level")
        }
        if (getModeSetting(modeNumber, "SetLevelOnlyOnce")) {
            def setLevelOnlyOnce = atomicState.setLevelOnlyOnce
            setLevelOnlyOnce[modeNumber] = true
            atomicState.setLevelOnlyOnce = setLevelOnlyOnce
        }
        log("BulbLevel ${getModeSetting(modeNumber, "BulbLevel")}")
        return getModeSetting(modeNumber, "BulbLevel")
    }
    //if no mode currently applies but there was one before, then re-set the level to the prior value
    else if (atomicState.levelBeforeModeLevel) {
        def level = atomicState.levelBeforeModeLevel
        atomicState.levelBeforeModeLevel = null //reset so next time it uses the old level
        return level
    }
    return 0
}

def isAnySceneOn() {
    if (scenes) {
        def scenesOn = scenes.findAll() {d -> d.latestValue("switch")=="on"}
        /*
        //check if was on in last 5 seconds
        def t0 = new Date(now() - (int)(1000 *5))
        def scenesOn = scenes.findAll() {d -> 
            recentStates = d.statesSince("switch", t0)
            recentStates.find{it.value == "on"}
        }*/
        if (scenesOn && scenesOn.size() > 0) {
            log("A scene is ON! $scenesOn")
            return true
        }
    }
    return false
}

//gets bulbs to operate on depending on the mode
def getBulbGroup() {
    return getBulbs(true)
}

//gets bulbs to operate on depending on the mode
def getBulbs(Boolean getGroupIfPossible=false) {
    //if no modes, return the group of bulbs
    if (!numOfModes || numOfModes == 0) {
        if (getGroupIfPossible) {
            return bulbGroup
        }
        else {
            return bulbs
        }
    }
    
    //default to the non-mode-specific bulbs
    def r = bulbs
    if (getGroupIfPossible) {
        r = [bulbGroup]
    }
    
    def modeNumber = getModeNumber()
    if (modeNumber > 0) {
        //check if bulbs were specified, if so return them
        if (getGroupIfPossible) {
            r = [getModeSetting(modeNumber, "BulbGroup")]
            
        }
        else {
            r = getModeSetting(modeNumber, "Bulbs")
        }
    }
    
    return r
}

def getIfPressTwiceOn() {
    if (atomicState.cache) {return atomicState.cache["getIfPressTwiceOn"]}
    def mode = getModeNumber()
    if (mode > 0) {
        return getModeSetting(getModeNumber(), "PressTwiceOn")
    }
    return false
}

def getModeNumber() {
    if (atomicState.cache) {return atomicState.cache["getModeNumber"]}
    for (def i=1; i<=numOfModes; i++) {
        if (getModeSetting(i, "mode") == location.mode)
        return i
    }
    return -1
}

def buttonHandler(evt) {
    def evtName = evt.name
    def evtValue = evt.value.toInteger()
    def evtDeviceId = evt.deviceId
    
    if (atomicState.ignoreNextSwitchButton && (now()-atomicState.ignoreNextSwitchButton) < atomicState.TIME_TO_IGNORE) {
        if (evtName == onOffEvent && ((evtValue == onValue && atomicState.ignoreNextSwitchButtonState=="on") ||
                                      (evtValue == offValue && atomicState.ignoreNextSwitchButtonState=="off"))) {
        
            log("IGNORING BUTTON HANDLER > $evtName $evtValue")
            atomicState.ignoreNextSwitchButton = 0
            return
        } else {
            log("NOT IGNORING BUTTON HANDLER > $evtName '$evtValue' '$onValue' as it's different than expected to be ignored ($atomicState.ignoreNextSwitchButtonState)")
        }
    }
    log("BUTTON HANDLER > $evtName $evtValue")
    evtValue = evtValue.toInteger()
    
    if (scenes && scenes.find { s -> s.switch=="on"}) { scenes*.off() }
    //unschedule()
    atomicState.abortExpectedStateCheck = now()+10
    if (evtName == onOffEvent && evtValue == onValue) {
        //turned on
        atomicState.ignoreBulbChange=now()
        atomicState.TIME_TO_IGNORE = atomicState.DEFAULT_TIME_TO_IGNORE //reset to default (shorter) ignore time. The ignore time may be longer after a bulb state was directly changed causing a bulb change event, then triggers a switch state change 

        //if already on, then toggle between mode and non-mode settings
        if (bulbGroup.latestValue("switch")=="on" && getIfPressTwiceOn()) {
            def currentlyInModeState = atomicState.isModeSpecificState
            atomicState.isModeSpecificState = !atomicState.isModeSpecificState

            //if not in the mode-specific state (i.e. all bulbs on), get the bulbs that should be off and turn them off
            if (!currentlyInModeState) {
                def diff = null
                if (atomicState.cache) {
                    diff = atomicState.cache["getDeviceDifference_bulbs_getBulbs"]
                }
                else {
                    diff = getDeviceDifference(bulbs, getBulbs())f
                }
                if (diff) {
                    log ("BUTTON HANDLER > turn off bulbs that should be off in this mode $diff")
                    diff*.off()
                }
            }
        }
        //log("mode-specific state: ${atomicState.isModeSpecificState}")
        
        atomicState.abortExpectedStateCheck = now() //refersh the time on the abort
        
        def setModeLevel = getBulbOnLevel()
        log("turning on bulbs to $setModeLevel")
        turnBulbsOn(setModeLevel)
        
        //if no defined mode level, and preference to set bulb level when turned on, and it's a dimmable switch, then set the bulbs to the dimmer level
        if (setModeLevel==0 && setBulbLevel && atomicState.controlIsDimmableSwitch) {
            syncBulbLevelToSwitch()
        }
        else {
            //if there is a defined mode level that the bulbs were set to, set the switch to that level immediatley. Pass the force on parameter to ensure the control switch is turned on (the bulb state on may not be set yet)
            if (setModeLevel>0) {
                syncSwitchToBulbs(setModeLevel, true)
            }
            else {
                //otherwise sync the switch to the bulbs in a few seconds when things settle down
                runIn(3, syncSwitchToBulbs)
            }
        }
        atomicState.ignoreBulbChange=now()
    }
    else if (evtName == onOffEvent && evtValue == offValue) {
        //turned off
        atomicState.TIME_TO_IGNORE = atomicState.DEFAULT_TIME_TO_IGNORE //reset to default (shorter) ignore time. The ignore time may be longer after a bulb state was directly changed causing a bulb change event, then triggers a switch state change

        atomicState.ignoreBulbChange=now()
        turnBulbsOff()
        atomicState.ignoreBulbChange=now()
        if (controlSwitch.latestValue("switch") == "on") {
            atomicState.ignoreSwitchChange=now()
            atomicState.ignoreNextSwitchButton=now()
            controlSwitch.off() //also turn off control switch. This normally is off if the physical paddle down is pressed, however it won't be done if a button event is sent to the switch
            atomicState.ignoreSwitchChange=now()
        }
        
        atomicState.abortExpectedStateCheck = now() //refersh the time on the abort
        
        atomicState.ignoreBulbChange=now()
        log("BUTTON HANDLER > turn off bulbs ($offValue)")
    }
    else if (evtName == dimEvent && evtValue == upValue) {
        //dim up
        
        if (atomicState.ignoreSwitchChange && (now()-atomicState.ignoreSwitchChange) < atomicState.TIME_TO_IGNORE) {
            log("BUTTON HANDLER > ignore level change")
            return
        }
        
        log("BUTTON HANDLER > start dimming")
        atomicState.ignoreSwitchChange=now()
        atomicState.ignoreBulbChange=now()
        atomicState.TIME_TO_IGNORE = atomicState.LONG_TIME_TO_IGNORE //ignore for much longer to avoid processing intermediate level events from switch
        atomicState.expectedLevel = -1
        
        if (bulbs[0].hasCommand("startLevelChange")) getBulbs()*.startLevelChange("up")
        ///runIn(2, subscribeToEvents)
    }
    else if (evtName == dimEvent && evtValue == downValue) {
        //turned dim down
        if (atomicState.ignoreSwitchChange && (now()-atomicState.ignoreSwitchChange) < atomicState.TIME_TO_IGNORE) {
            log("BUTTON HANDLER > ignore level change $evt.value")
            return
        }
        
        
        atomicState.ignoreSwitchChange=now()
        atomicState.ignoreBulbChange=now()
        atomicState.TIME_TO_IGNORE = atomicState.LONG_TIME_TO_IGNORE //ignore for much longer to avoid processing intermediate level events from switch
        atomicState.expectedLevel = -1

        if (bulbs[0].hasCommand("startLevelChange")) getBulbs()*.startLevelChange("down")
    }
    else if (evtName == "released") {
        //release dim up or down
        atomicState.TIME_TO_IGNORE = atomicState.DEFAULT_TIME_TO_IGNORE //revert back to the 500ms time to ignore
        if (evtValue == upValue) {
            atomicState.ignoreBulbChange=now()
            if (bulbs[0].hasCommand("startLevelChange")) getBulbs()*.stopLevelChange()
            //syncBulbLevelToSwitch(-1)
            if (refreshAfterDim) runInMillis(1500, "syncBulbLevelToSwitch", [data:-1])
            //runInMillis(1500, "syncBulbLevelToSwitch", [data:-1])
            atomicState.ignoreBulbChange=now()
        }
        else if (evtValue == downValue) {
            atomicState.ignoreBulbChange=now()
            if (bulbs[0].hasCommand("startLevelChange")) getBulbs()*.stopLevelChange()
            //syncBulbLevelToSwitch(-1)
            if (refreshAfterDim) runInMillis(1500, "syncBulbLevelToSwitch", [data:-1])
            atomicState.ignoreBulbChange=now()
            //log("released, stop level change")
        }
        //controlSwitch.refresh()
        //refreshDelay(evtDeviceId, "refresh", 50)
        if (atomicState.controlIsDimmableSwitch) {
            //zooz zen30 needs nger delaylo
            if (refreshAfterDim) refreshDelay(evtDeviceId, "refresh", 550)
        }
        //controlSwitch.refresh([delay:1000]) //if doing multiple switches should ONLY refesh the calling switch
        //runIn(3, syncSwitchToBulbs)
    }
}

def turnBulbsOff() {
    unschedule()
    atomicState.expectedSwitch = "off"
    //bulbs.off()
    atomicState.ignoreBulbChange=now()+2000
    bulbGroup.off()
    atomicState.isModeSpecificState = true
    runInMillis(4500, "checkExpectedState", [data: 1])
}

def turnBulbsOn(level=0) {
    atomicState.expectedSwitch = "on"
    
    //8/12/2020 bulbGroup.on()
    //log("gbg  ****"+getBulbGroup(),true)
    atomicState.ignoreBulbChange=now()+2000
    if (level>0) {
        //getBulbGroup()*.on() 1/30/2010
        atomicState.expectedLevel = level
        getBulbGroup()*.setLevel(level)
        //runIn(3, syncSwitchToBulbs)
    }
    else {
        getBulbGroup()*.on()
        atomicState.expectedLevel = -1 //keep the current level
        //runIn(3, syncSwitchToBulbs)
    }
    
    runInMillis(4500, "checkExpectedState", [data: 1])
}

//check & set bulb's expected on/off state, and level if on
def checkExpectedState(attempt) {
    if (checkIfShouldAbortExpectedStateCheck("!ABORT! checkExpectedState $attempt")) return //abort if button was pressed

     switch(attempt) { 
       case 1: 
           if (setExpectedState()) {
               log("found some bulbs out of sync attempt: $attempt")
               runInMillis(1500, "checkExpectedState", [data:attempt+1])
           }
           else {
                if (checkIfShouldAbortExpectedStateCheck("!ABORT! checkExpectedState > postCheck $attempt")) return //abort if button was pressed

               log("all bulbs OK")
               //atomicState.expectedSwitch = "u"
               atomicState.expectedLevel = -1
               runIn(3, syncSwitchToBulbs)
               atomicState.ignoreBulbChange = now()
           }
           break
       case 2:
           if (setExpectedState()) {
               log("found some bulbs out of sync attempt: $attempt")
               runInMillis(2100, "checkExpectedState", [data:attempt+1])
           }
           else {
                if (checkIfShouldAbortExpectedStateCheck("!ABORT! checkExpectedState > postCheck $attempt")) return //abort if button was pressed

               log("all bulbs OK")
               //atomicState.expectedSwitch = "u"
               atomicState.expectedLevel = -1
               runIn(3, syncSwitchToBulbs)
               atomicState.ignoreBulbChange = now()
           }
           break
       case 3:
           if (setExpectedState()) {
               log("found some bulbs out of sync attempt: $attempt")
               runInMillis(3100, "checkExpectedState", [data:attempt+1])
           }
           else {
                if (checkIfShouldAbortExpectedStateCheck("!ABORT! checkExpectedState > postCheck $attempt")) return //abort if button was pressed

               log("all bulbs OK")
               //atomicState.expectedSwitch = "u"
               atomicState.expectedLevel = -1
               runIn(3, syncSwitchToBulbs)
               atomicState.ignoreBulbChange = now()
           }
           break
       case 4:  //also default
           if (setExpectedState()) {
               log("found some bulbs out of sync in final attempt: $attempt")
               //runInMillis(4000, "checkExpectedState", [data:attempt+1])
           } 
           else {
                if (checkIfShouldAbortExpectedStateCheck("!ABORT! checkExpectedState > postCheck $attempt")) return //abort if button was pressed

               log("all bulbs OK")
               //atomicState.expectedSwitch = "u"
               atomicState.expectedLevel = -1
               runIn(3, syncSwitchToBulbs)
               //atomicState.ignoreBulbChange = now()
           }
           break
    }
}

def checkIfShouldAbortExpectedStateCheck(msg) {
    if (atomicState.abortExpectedStateCheck && ((now()-atomicState.abortExpectedStateCheck) <= (atomicState.TIME_TO_IGNORE/2))) {
        log(msg)
        return true
    }
    if (isAnySceneOn()) {
        log("$msg. Abort because SCENE IS ON!")
        return
    }
    //log("OK DO NOT $msg")
    return false
}

def computeAvgBulbLevel() {
    def sum = 0
    def count = 0
    getBulbs().each{ d -> 
        if (d.latestValue("switch") == "on") {
            sum += d.latestValue("level")
            count += 1
        }
    }
    def level = 0
    if (count > 0) {
        level = sum/count
    }
    if (level == 0) {
        level = bulbGroup.latestValue("level")
    }
    return level
}

//set bulb's expected on/off state, and level if different from switch
def setExpectedState() {
    def anyOn = false
    //def expectedSwitch = atomicState.expectedSwitch
    def expectedLevel = atomicState.expectedLevel
    
    if (expectedLevel > 99) {
        expectedLevel = 99
    }
    
    /*if (expectedSwitch == bulbGroup.latestValue("switch")) {
        log.debug "All good: AS  ${atomicState.expectedSwitch}; BG: ${bulbGroup.latestValue('switch')}"
        return
    }*/
    
    
    if (!atomicState.controlIsDimmableSwitch) return false

    if (atomicState.expectedSwitch == "u") {
        return false
    }
    
    def wrongLevel = false
     
    def wrongStateBulbs = getBulbs().findAll() { d ->
        if (checkIfShouldAbortExpectedStateCheck("!ABORT! setExpectedState - findAll")) return false //abort if button was pressed

        if (d.latestValue("switch") != atomicState.expectedSwitch) {
            log("STATE not correct $d.label: ${d.latestValue("switch") != atomicState.expectedSwitch} = DeviceValue(${d.latestValue("switch")}) != ExpectedState(${atomicState.expectedSwitch};) BGV(${bulbGroup.latestValue("switch")})")
            return true
        }
        def deviceLevel = d.latestValue("level").toInteger()
        if (deviceLevel > 99) {deviceLevel = 99}
        if (atomicState.expectedSwitch == "on" && expectedLevel > 0 && deviceLevel != expectedLevel) {
            wrongLevel = true
            log("LEVEL not correct $d.label: ${deviceLevel != atomicState.expectedSwitch} = DeviceValue(${deviceLevel}) != ExpectedLevel(${expectedLevel};) BGV(${bulbGroup.latestValue("switch")})")
            return true
        }
        return false
    }
    
    if (checkIfShouldAbortExpectedStateCheck("!ABORT! setExpectedState - after findAll")) return false //abort if button was pressed

    
    if (wrongStateBulbs.size() >0) {
        //log(wrongStateBulbs)
        if (atomicState.expectedSwitch == "on") {
            atomicState.ignoreBulbChange = now()+2000
            //if level is wrong, then set level; otherwise just turn on the lights
            if (wrongLevel) {
                getBulbGroup()*.setLevel(expectedLevel)
            }
            else {
                getBulbGroup()*.on()
                
            }
            
            //wait for new state to set, then refresh
            atomicState.ignoreBulbChange = now()+2000
            pauseExecution(500)
            wrongStateBulbs?.each { if (it.hasCommand("refresh")) it.refresh() }
            pauseExecution(500)
            /* if (wrongStateBulbs.size() == bulbs.size()) {
                bulbGroup.on()
            }
            else {
                wrongStateBulbs*.on()
            }*/
        }
        else {
            log ("wrong state of these bulbs; turn them off: $wrongStateBulbs")
            atomicState.ignoreBulbChange = now()+3500
            bulbGroup.off()
            
            //wait for new state to set, then refresh  
            atomicState.ignoreBulbChange = now()+3500
            pauseExecution(500)
            wrongStateBulbs?.each { if (it.hasCommand("refresh")) it.refresh() }
            pauseExecution(500)
            /*if (wrongStateBulbs.size() == bulbs.size()) {
                bulbGroup.off()
            }
            else {
                wrongStateBulbs*.off()
            }*/
        }
        return true
    }
    return false
}

def refreshDelay(devId, command, delayMS) {
    runInMillis(delayMS, commander, [data: [dev: devId, cmd: command]])
}

def commander(data) {
    //def dev = controlSwitches.find {it.deviceId == data.dev} // NEED TO UPDATE IF SUPPORTING MULTIPLE SWITCHES
    def dev =  controlSwitch
    def cmd = data.cmd
    dev."$cmd"()
}

def subscribeSwitches(dev) {
    if (!atomicState.controlIsSwitch ) { return }
    if (dev == controlSwitch) {
        subscribe(controlSwitch, "switch", controlSwitchHandler, [filterEvents: true])
        subscribe(controlSwitch, "level", controlLevelHandler, [filterEvents: true])
    }
    else if (dev == bulbGroup) {
        subscribe(bulbGroup, "switch", bulbGroupSwitchHandler, [filterEvents: true])
        subscribe(bulbGroup, "level", bulbGroupLevelHandler, [filterEvents: true])
    }
}

def controlSwitchHandler(evt) {
    log("switch $evt.value")
    /*
    if (atomicState.ignoreSwitchChange && (now()-atomicState.ignoreSwitchChange) < atomicState.TIME_TO_IGNORE) {
        log("ignore level change $evt.value")
        return
    }

    if (evt.name == "switch" && evt.value == "on") {
        atomicState.ignoreBulbChange=now()
        turnBulbsOn(getBulbOnLevel())
        atomicState.ignoreBulbChange=now()
    }
    else if (evt.name == "switch" && evt.value == "off") {
        atomicState.ignoreBulbChange=now()
        turnBulbsOff()
        atomicState.ignoreBulbChange=now()
    }*/
}
def bulbGroupSwitchHandler(evt) {
    log("BULB GROUP SWITCH HANDLER > group $evt.value")

    if (atomicState.ignoreBulbChange && (now()-atomicState.ignoreBulbChange) < atomicState.TIME_TO_IGNORE) {
        log("BULB GROUP SWITCH HANDLER > ignore switch change $evt.value")
        return
    }
    if (!atomicState.controlIsDimmableSwitch) return
    
    if (isAnySceneOn()) {
        log("BULB GROUP SWITCH HANDLER > A scene is on!")
        return
    }

    if (evt.name == "switch" && evt.value == "on") {
        atomicState.ignoreSwitchChange=now()
        atomicState.TIME_TO_IGNORE = atomicState.LONG_TIME_TO_IGNORE //ignore for much longer to avoid processing events from switch (e.g. if only 1 of 4 bulbs is turned on, we don't want the switch handler to trigger and turn on the other 3)
        //controlSwitch.on()
        atomicState.expectedSwitch = "on"
        atomicState.ignoreNextSwitchButton=now()
        atomicState.ignoreNextSwitchButtonState="on"
        syncSwitchToBulbs(bulbGroup.latestValue("level"), true) //turn on switch and set to bulb group level
        atomicState.ignoreSwitchChange=now() +3000 //set this to longer than was set in function above
    }
    else if (evt.name == "switch" && evt.value == "off") {
        atomicState.ignoreSwitchChange=now()
        atomicState.TIME_TO_IGNORE = atomicState.LONG_TIME_TO_IGNORE //ignore for much longer to avoid processing events from switch (e.g. if only 1 of 4 bulbs is turned off, we don't want the switch handler to trigger and turn off the other 3)
        atomicState.ignoreNextSwitchButton=now()
        atomicState.ignoreNextSwitchButtonState="off"
        controlSwitch.off()
        atomicState.expectedSwitch = "off"
        atomicState.isModeSpecificState = true
        atomicState.ignoreSwitchChange=now() +3000
    }
}

def bulbHandler(evt) {
    if (!isAnySceneOn() && colorTempBulb && evt.name == "switch" && evt.value == "on") {
        def dev = evt.getDevice()
        def correctCT = 0
        if (colorTempBulb) {
            correctCT = colorTempBulb.latestValue("colorTemperature").toInteger()
        }
        else if (colorTempBulbVar) {
            correctCT = getGlobalVar(colorTempBulbVar).value
        }

        def deviceCT = dev.latestValue("colorTemperature").toInteger()
        if (deviceCT != correctCT) {
            log("incorrect CT")
            //dev.setColorTemperature(correctCT)
            getBulbGroup()*.setColorTemperature(correctCT)
        }
    }
}

def controlLevelHandler(evt) {
    /*if (atomicState.ignoreSwitchChange && (now()-atomicState.ignoreSwitchChange) < atomicState.TIME_TO_IGNORE) {
        log("ignore switch level change $evt.value expectedSwitch: $atomicState.expectedSwitch switchState: ${controlSwitch.latestValue("switch")}")
        return
    }
    log("switch level $evt.value expectedSwitch: $atomicState.expectedSwitch switchState: ${controlSwitch.latestValue("switch")}")
    
    if (evt.name == "level") {
        if (atomicState.expectedSwitch!="off" && controlSwitch.latestValue("switch")=="on"){
        
            syncBulbLevelToSwitch(Integer.parseInt(evt.value))
        }
        //def val = Integer.parseInt(evt.value)
        //unsubscribe(bulbGroup)
        //if ( val < minLevel) {
        //   bulbGroup.setLevel(minLevel)
        //}
        //else {
        //    bulbGroup.setLevel(val)
        //}
    }*/
    
    //runIn(2, subscribeToEvents)
}
def bulbGroupLevelHandler(evt) {
    if (atomicState.ignoreBulbChange && (now()-atomicState.ignoreBulbChange) < atomicState.TIME_TO_IGNORE) {
        log("BULB GROUP LEVEL HANDLER ignore level change $evt.value")
        return
    }
    log("BULB GROUP LEVEL HANDLER group level $evt.value")
    if (!atomicState.controlIsDimmableSwitch) return
    
    if (isAnySceneOn()) {
        log("BULB GROUP LEVEL HANDLER > A scene is on!")
        return
    }
    
    //check state so that the indidual bulb levels can be refreshed if needed
    runInMillis(4500, "checkExpectedState", [data: 1])
    runIn(5, syncSwitchToBulbs)
    //sync switch to the new level after some time
    runIn(15, syncSwitchToBulbs2)

    /*if (evt.name == "level") {
        def val = Integer.parseInt(evt.value)
        atomicState.ignoreSwitchChange=now()
        atomicState.TIME_TO_IGNORE = atomicState.LONG_TIME_TO_IGNORE //ignore for much longer to avoid processing events from switch (e.g. if only 1 of 4 bulbs has level changed, we don't want the switch handler to trigger and set the level of the other 3)
        
        controlSwitch.setLevel(val)
   
        atomicState.ignoreSwitchChange=now()
    }*/
}
def syncSwitchToBulbs2() {
    syncSwitchToBulbs()
}

def syncSwitchToBulbs(level=0, forceOn=false) {
    if (controlSwitch.latestValue("switch") != bulbGroup.latestValue("switch")) {
        if (bulbGroup.latestValue("switch") == "on" || forceOn) {
            atomicState.ignoreSwitchChange=now()
            //this will cause a button event to be sent. To avoid duplicates, ignore the next button even
            atomicState.ignoreNextSwitchButton=now()
            atomicState.ignoreNextSwitchButtonState="on"
            controlSwitch.on()
        }
        else {
            //log("turning off control switch to sync with bulbs being off")
            atomicState.ignoreNextSwitchButton=now()
            atomicState.ignoreNextSwitchButtonState="off"
            //this will cause a button event to be sent. To avoid duplicates, ignore the next button even
            controlSwitch.off()
            atomicState.ignoreNextSwitchButton=now()
            atomicState.isModeSpecificState = true
        }

        atomicState.ignoreSwitchChange=now()
    }

    if (!atomicState.controlIsDimmableSwitch) return
    if (controlSwitch.latestValue("switch") == "on" || level > 0) {
        def desiredLevel = 0
        if (level > 0) {
            desiredLevel = level
        }
        else {
            desiredLevel = computeAvgBulbLevel()//bulbGroup.latestValue("level")
        }
        log ("DESIRED LEVEL $desiredLevel")
        if (controlSwitch.latestValue("level") != desiredLevel) {
            //unsubscribe(controlSwitch)
            atomicState.ignoreSwitchChange=now()
            controlSwitch.setLevel( desiredLevel)
            atomicState.ignoreSwitchChange=now()
        }
    }
}

def syncBulbLevelToSwitch(levelVal=-1) {
    if (checkIfShouldAbortExpectedStateCheck("!ABORT! syncBulbLevelToSwitch")) return //abort if button was pressed
    unschedule()
    atomicState.abortExpectedStateCheck = now()
    
    /*if (isAnySceneOn()) {
        log("syncBulbLevelToSwitch >> A scene is ON!")
        return
    }*/
    
    if (!atomicState.controlIsDimmableSwitch) return
    def level = levelVal
    if (levelVal == -1) {
        level = controlSwitch.latestValue("level")
    }
    if ( level < minLevel) {
        level = minLevel
    }
    //set expected level and exptect switch state to on so that checkExpectedState can verif the state is correct
    atomicState.expectedLevel = level
    atomicState.expectedSwitch = "on"
    atomicState.ignoreBulbChange = now() +2000
    getBulbGroup()*.setLevel(level)
    atomicState.ignoreBulbChange = now() +2000
    
    log("sync level $level")
    
    runInMillis(1500, "checkExpectedState", [data: 1]) 
    //runIn(2, subscribeToEvents) //instead of doing this here, do it after checkExpectedState succeeds
    //atomicState.ignoreBulbChange = 0
}

def logsOff(){
    log.warn "debug logging disabled..."
    logEnable = false
}

  