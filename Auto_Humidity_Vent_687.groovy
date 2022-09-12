/**
 *  Auto Humidity Vent
 *
 *  Copyright 2014 Jonathan Andersson, modified by Nektarios to work with % changes over past average humidity, and more

LAST UPDATED: 10/20/2020


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
 
 
definition (

    name: "Auto Humidity Vent",
    namespace: "jonathan-a, nektarios",
    author: "Jonathan Andersson modified by Nek",
    description: "When the humidity reaches a specified level, activate one or more vent fans until the humidity is reduced to a specified level.",
    category: "Convenience",
    iconUrl: "https://s3.amazonaws.com/smartthings-device-icons/Appliances/appliances11-icn.png",
    iconX2Url: "https://s3.amazonaws.com/smartthings-device-icons/Appliances/appliances11-icn@2x.png"

)


preferences {

    section("Enable / Disable the following functionality:") {
        input "appEnabled", "bool", title: "Auto Humidity Vent", required:true, defaultValue:true
        input "fanControlEnabled", "bool", title: "Vent Fan Control", required:true, defaultValue:true
    }

    section("Choose a humidity sensor...") {
        input "humiditySensor", "capability.relativeHumidityMeasurement", title: "Humidity Sensor", required: true
    }
    section("Enter the relative humudity level (%) above and below which the vent fans will activate and deactivate:") {
        input "humidityActivateLevel", "number", title: "Humidity Activation Level", required: false, defaultValue:0
        input "humidityDeactivateLevel", "number", title: "Humidity Deactivation Level", required: false, defaultValue:0
    }
    
    section("Alternatively, enter the humudity change (%) above which the vent fans will activate. Fans will deactivate when they return to the pre-activate level:") {
        input "humidityActivateChange", "number", title: "Humidity Activation Level", required: false, defaultValue:5
    }

    section("Select the vent fans to control...") {
        input "fans", "capability.switch", title: "Vent Fans", multiple: true, required: true
    }
    
    section("Only turn off fans if these lights are also off...") {
        input "lights", "capability.switch", title: "Lights", multiple: true, required: false
    }

    section("Turn off after this amount of time, if humidity is low and (if specified) the lights are off") {
        input "timerOff", "number", title: "Time after on to turn off",default: 10, required: false
    }
    
    section("Turn off after this amount of time regardless of any other condition") {
        input "maxTimeOn", "number", title: "Maximum time to stay on",default: 60, required: false
    }
    section("Dimmer whose level will be set to target humidity") {
        input "startHumidityLevel", "capability.switchLevel", title:"Target Level Dimmer", multiple:false, required:false
    }
    section("Dimmer whose level will be set to current humidity") {
        input "currentHumidityLevel", "capability.switchLevel", title:"Current Level Dimmer", multiple:false, required:false
    }

    section("Set notification options:") {
        input "sendPushMessage", "bool", title: "Push notifications", required:true, defaultValue:false
        input "phone", "phone", title: "Send text messages to", required: false
    }


}


def installed() {

    log.debug "${app.label} installed with settings: ${settings}"
  
    initialize()

}

def uninstalled()
{

    send("${app.label} uninstalled.")
    
    state.appEnabled = false

    set_fans(false)

    state.fanControlEnabled = false

}


def updated() {

    log.debug "${app.label} updated with settings: ${settings}"

    unsubscribe()

    initialize()
    checkHumidity()
    //testDebug()
}

def testDebug() {
    def inPastMin = 240
    def minAgo = 10
    def currTime = Date.parse("5/30/2017  12:37:09 AM PDT")
    //log.debug("***" + new Date(currTime).getDateTimeString())
    
    def recentStates = []

    def oldTime = null
    def recentTime = null

    //if we need to go further back to check the humidity, keep track of how far back we're going
    def nextMinAgo = minAgo

    def c = 0
    while (recentStates.size()==0 && nextMinAgo < 2880) { //keep checking for past 48 hours
        //get average humidity *before the past minAgo minutes, for the past inPastMin minutes
        minAgo = nextMinAgo
        oldTime = new Date((long) currTime - (minAgo * 60 *1000))
        recentTime = new Date((long)currTime - (inPastMin * 60 *1000))

        log.debug "***" + c + " Between ${oldTime.getDateTimeString()} and ${recentTime.getDateTimeString()}"
        
        //states between recentTime .. start time
        recentStates = humiditySensor.eventsBetween(oldTime, recentTime, [max:1000]).findResults{ ev ->
            if (ev.getName() =="humidity") {
                return ev
            }
            return null
        }
                                                                                                    
        nextMinAgo = nextMinAgo + 60
        c = c + 1
    }    
    
    log.debug "****" + recentStates

    //go throug the states (oldest first) and see what the humidity was, on average
    def lastTime = 0
    def lastDuration = 0
    def lastHumidity = 0
    def numMinutes = 0
    def sumHumidity = 0
    for (def i = recentStates.size()-1; i>=0; i--)  {
        if (lastTime == 0) {
            lastTime = recentStates[i].date.getTime()
            log.debug("***" + new Date(lastTime).getDateTimeString())
            lastDuration = 0
        }
        else {
            lastDuration = (recentStates[i].date.getTime() - lastTime)/1000/60
        }
        
        numMinutes = numMinutes + lastDuration
        //sumHumidity = sumHumidity + (lastHumidity)*lastDuration //12-28 change
        //instead of assuming the humidity was the previous humidity, assume it was an average of thh current and last humidity
        
        def avg = 0
        if (numMinutes >0 ) {
            avg = sumHumidity/numMinutes
        }
        log.debug "humidity at ${recentStates[i].date.format("h:mm:ss a MM-dd", location.timeZone)}: ${recentStates[i].value} runningavg: ${avg}"
       
        sumHumidity = sumHumidity + ( (lastHumidity+recentStates[i].value.toFloat())/2 )*lastDuration 
        
        //log.debug "Time${recentStates[i].date} Duration ${currentDuration} Humidity ${recentStates[i].value}"
        lastTime = recentStates[i].date.getTime()
        lastHumidity = recentStates[i].value.toFloat()
    }
    //for the last change, use recentTime as the latest time
    lastDuration = (recentTime.getTime() - lastTime)/1000/60
    numMinutes = numMinutes + lastDuration
    sumHumidity = sumHumidity + (lastHumidity)*lastDuration
    
    
    def avgHumidity = 0
    
    if (numMinutes!=0) {
        avgHumidity = sumHumidity/numMinutes
    }
    if (avgHumidity<= 0) {
        avgHumidity = getCurrentHumidity()
    }
    
    log.debug "***" + "Between ${minAgo} ago and ${inPastMin}: Mins ${numMinutes}, Avg ${(avgHumidity)}, "
    
    log.debug "***" + avgHumidity

}


def initialize() {
    state.clear()
    
    state.fansOnTime = now()
    state.fansLastRunTime = 0
    
    //subscribe(humiditySensor, "humidity", humidityChangeEvent)

    state.humidityThresholdActivated = false
    state.autoTurnedOn = false
    
    //resetMultiplier()
    
    if (!state.log) {
        state.log = [:]
    }
    
    if (!state.humidityLog) {
        state.humidityLog = []
    }
    if (!state.humidityTenLog) {
        state.humidityTenLog = []
    }
    
    
    if (settings.fanControlEnabled) {
        if(state.fanControlEnabled == false) {
            send("Vent Fan Control Enabled.")
        } 
        log.debug "Vent Fan Control Enabled."
        state.fanControlEnabled = true
    } 
    else {
        if(state.fanControlEnabled == true) {
            send("Vent Fan Control Disabled.")
        }
        log.debug "Vent Fan Control Disabled."
        state.fanControlEnabled = false
    }

    if (settings.appEnabled) {
        if(state.appEnabled == false) {
            send("${app.label} Enabled.")
        }
        log.debug "${app.label} Enabled."
        
        subscribe(humiditySensor, "humidity", humidityChangeEvent) //, [filterEvents: false]
        subscribe(fans, "switch", fanTurnedOnOff)
        subscribe(lights, "switch", lightsTurnedOnOff)
        subscribe(remote, "button", buttonPressed)

        state.appEnabled = true
    } else {
        if(state.appEnabled == true) {
            send("${app.label} Disabled.")
        } 
        log.debug "${app.label} Disabled."
        
        state.appEnabled = false
    }
    
    humidityChangeEvent(null)
}

def buttonPressed(evt) {
    def jsonSlurper = new groovy.json.JsonSlurper()
    def object = jsonSlurper.parseText(evt.data)
    log.debug(object.buttonNumber)
}

def humidityChangeEvent(evt) {
    if(evt) {
        log.debug "humidityChangeEvent() ${evt.descriptionText}"
        state.log["lastHumidityChangeEvent"] = formattedDate()
    }
    else {
        log.debug "humidityChangeEvent()"
    }

    runIn(10, checkHumidity)
    runIn(2 * 60, checkHumidity2)
}

def checkHumidity2() {
    state.log["lastCheckHumidity2"] = formattedDate()
    checkHumidity()
}

def checkHumidity() {
    log.debug "checkHumidity()"
    state.log["lastCheckHumidity"] = formattedDate()
    def fansOn = areFansOn()
    def currentHumidity = getCurrentHumidity()

    log.debug "checkHumidity() Humidity: $currentHumidity%, Activate: $humidityActivateLevel%, Deactivate: $humidityDeactivateLevel% , Change Activate: $humidityActivateChange%, FansOn: $fansOn"
    
    if (settings.appEnabled) {
        if (settings.humidityActivateLevel > 0 && settings.humidityDeactivateLevel > 0) {
            if (fansOn) {
                if (checkIfHumidityIsLow(currentHumidity)) {
                    log.debug "Humidity sufficient to deactivate vent fans: $currentHumidity <= $humidityDeactivateLevel"
                    set_fans(false, false)
                    state.log["autoThresholdTurnedOffTime"] = formattedDate()
                } else {
                    log.debug "Humidity not sufficient to deactivate vent fans: $currentHumidity > $humidityDeactivateLevel"
                }
            } else if (!fansOn) {
                if (checkIfHumidityIsHigh(currentHumidity)) {
                    log.debug "Humidity sufficient to activate vent fans: $currentHumidity >= $humidityActivateLevel"
                    set_fans(true, true)
                    state.log["autoThresholdTurnedOnTime"] = formattedDate()
                } else {
                    log.debug "Humidity not sufficient to activate vent fans: $currentHumidity < $humidityActivateLevel"
                }
            }
            if (startHumidityLevel) { startHumidityLevel.setLevel(currentHumidity) }
            if (currentHumidityLevel) { currentHumidityLevel.setLevel(currentHumidity) }
        }
        else {
            if (settings.humidityActivateChange > 0) {
                //log.debug "About to check avg humidity"
                def avgHumidity = getAvgHumidity(10, 240)
                def avgHumidityTen = getAvgHumidity(1, 10)
                log.debug "AvgHumidity (10-240): ${avgHumidity}; (1-10): ${avgHumidityTen}; Is AutoOn: ${state.autoTurnedOn} "
                
                currentHumidity = getCurrentHumidity()
                               
                state.humidityLog.push(formattedDate() + "\t@\t" + (Math.round(avgHumidity*100)/100 + "\t${fansOn}\t${currentHumidity}\t${(Math.round(avgHumidityTen*100)/100)}" ))
                state.humidityTenLog.push(formattedDate() + "@" + (Math.round(avgHumidityTen*100)/100))
                
                if (state.humidityLog.size() > 100) {
                    state.humidityLog = state.humidityLog.drop(state.humidityLog.size() - 100)
                }
                if (state.humidityTenLog.size() > 100) {
                    state.humidityTenLog = state.humidityTenLog.drop(state.humidityTenLog.size() -  100)
                }
                
                state.lastMeasuredAvgHumidity = avgHumidity
                state.lastMeasuredAvgHumidityTen = avgHumidityTen

                if (avgHumidity>0){

                    //h = getCurrentHumidity()
                    //h = currentHumidity

                    if (!fansOn && currentHumidity > (avgHumidity + settings.humidityActivateChange)) { //commented 2017-13-06
                    //if (!fansOn && currentHumidity > (avgHumidityTen + settings.humidityActivateChange)) { //new 2017-13-06
                    
                        //log.debug "High humidity. Activate fans. Humidity: $currentHumidity%, AvgHumidity: $avgHumidity"
                        //state.humidityBeforeIncrease = avgHumidity + humidityActivateChange
                        //state.humidityBeforeIncrease = avgHumidity + humidityActivateChange //change to this on 12/28

                        //state.humidityBeforeIncrease = avgHumidityTen+1 //commented on 11/14/2016
                        state.humidityBeforeIncrease = currentHumidity //changed to this on 11/14/2016
                        //state.humidityTriggerOff = avgHumidity+1 //changed to this on 11/14/2016
                        
                        def humidityOffChange = settings.humidityActivateChange / 2
                        if (humidityOffChange > 8) {
                            humidityOffChange = 8
                        }
                        state.humidityTriggerOff = avgHumidity + humidityOffChange
                        
                        //set level of tracking dimmers
                        if (startHumidityLevel) { startHumidityLevel.setLevel(state.humidityTriggerOff) }
                        if (currentHumidityLevel) { currentHumidityLevel.setLevel(currentHumidity) }


                        send("High humidity. Activate fans. Humidity: $currentHumidity%, AvgHumidity: $avgHumidity, AvgHumidityTen: $avgHumidityTen, HumidityBeforeIncrease: $state.humidityBeforeIncrease")
                        state.log["autoTurnedOnTime"] = formattedDate()

                        set_fans(true, true)
                    }
                    //else if (fansOn && state.autoTurnedOn && currentHumidity <= (state.humidityBeforeIncrease)) { //removed +1 from being added to humidityBeforeIncrease on 11/14; commented 2017/6/13
                    else if (fansOn && state.autoTurnedOn && currentHumidity <= (state.humidityTriggerOff)) { //changed 2017/6/13
                        //log.debug "Normal humidity. Deactivate fans. Humidity: $currentHumidity%, AvgHumidity: $avgHumidity, HumidityBeforeIncrease: $state.humidityBeforeIncrease%"
                        send("Normal humidity. Deactivate fans. Humidity: $currentHumidity%, AvgHumidity: $avgHumidity, HumidityBeforeIncrease: $state.humidityBeforeIncrease%")
                        state.log["autoTurnedOffTime"] = formattedDate()
                        set_fans(false, false)

                        //set level of tracking dimmers
                        if (startHumidityLevel) { startHumidityLevel.setLevel(currentHumidity) }
                        if (currentHumidityLevel) { currentHumidityLevel.setLevel(currentHumidity) }
                    }
                    else {
                        //set level of tracking dimmers
                        if (!state.autoTurnedOn) {
                            if (startHumidityLevel) { startHumidityLevel.setLevel(currentHumidity) }
                        }
                        if (currentHumidityLevel) { currentHumidityLevel.setLevel(currentHumidity) }

                        log.debug "checkHumidity() NO action: FansOn: ${fansOn}, CurrentHumidity ${currentHumidity}, AvgHumidity ${avgHumidity}, ON Threshold ${avgHumidity+settings.humidityActivateChange}" //commented 2017-13-06
                        if (!fansOn && currentHumidity > (avgHumidityTen + settings.humidityActivateChange)) {
                            log.debug "IF using avgHumidityTen ${avgHumidityTen}, then fans would have activated!"
                        }
                        //log.debug "checkHumidity() NO action: FansOn: ${fansOn}, CurrentHumidity ${h}, ON Threshold ${avgHumidity+settings.humidityActivateChange}"  //new 2017-13-06
                    }
                }
            }
            else {
                if (startHumidityLevel) { startHumidityLevel.setLevel(currentHumidity) }
                if (currentHumidityLevel) { currentHumidityLevel.setLevel(currentHumidity) }
            }
        }
    }

}

def areFansOn() {
    return fans.findAll{it.latestValue("switch").toUpperCase() == "ON"}.size()!=0
}

def areLightsOn() {
    return lights.findAll{it.latestValue("switch").toUpperCase() == "ON"}.size()!=0
}

def fanTurnedOnOff(evt) {
    if (evt.value == "on") {
        //fan turned on
        if (!state.autoTurnedOn) {
            log.debug "Turning on timer for ${timerOff} since fan was turned on"
            setFanCountDown()
        }
    }
    else if (evt.value == "off") {
        unschedule(forceTurnOff)
        unschedule(turnOff)
        //runIn(60, resetMultiplier)
        state.autoTurnedOn = false
    }
}

def getTimeMultiplier() {
    if (state.autoTurnedOn) {
        return 2.5
    }
    else {
        return 1.0
    }
}

def setFanCountDown() {
    if (timerOff != null) {
        runIn((int)(timerOff * 60 * getTimeMultiplier()), turnOff)
    }
    if (maxTimeOn != null) {
        runIn((int)(maxTimeOn * 60 * getTimeMultiplier()), forceTurnOff)
    }
}

def lightsTurnedOnOff(evt) {
    if (evt.value == "off") {
        log.debug "Turning on timer for ${timerOff * getTimeMultiplier()} since fan was turned on and lights are off"
        if (timerOff != null) {
            runIn((int)(timerOff * 60 * getTimeMultiplier()), turnOff)
        }
    }
}

def turnOff() {
    if (!areFansOn()) {
        log.debug "Fans are already off"
        state.log["lastTurnOff_fansAlreadyOff"] = formattedDate()
    }
    else {
        state.log["lastTurnOff_fansOn"] = formattedDate()
        if (!state.autoTurnedOn) {
            //if didn't auto-turn on...
            //if lights are on, postpone, otherwise turn off
            if (lights && areLightsOn()) {
                log.debug "Postpone turning off; lights on"
                runIn((int)(timerOff * 60 * getTimeMultiplier()), turnOff)
            }
            else {
                log.debug "Turning off fan"
                send("Time is up. Deactivate fans")
                humidityChangeEvent(null)
                set_fans(false)
                //resetMultiplier()
            }
        }
        else {
            //if auto-turned on, check if humidity is low enough to change by manually calling event handlery
            humidityChangeEvent(null)
            //if auto-turned on, check if humidity is low enough to turn off
            //do nothing -- just wait for the humidity change event
            
            //if too high, postpone 
            //if (humidityActivateLevel > 0 && !checkIfHumidityIsLow(0)) {
            //  log.debug "Postpone turning off; humidity too high"
            //  runIn(timerOff * 60, turnOff)
            //}
        }        
    }
}

def forceTurnOff() {
    if (!areFansOn()) {
        log.debug "Fans are already off"
        state.log["lastForceTurnOff_fansAlreadyOff"] = formattedDate()
    }
    else {
        state.log["lastForceTurnOff_fansOn"] = formattedDate()
        send("Max time is up. Deactivate fans")
    }
    set_fans(false, false)
    //resetMultiplier()
}

def getCurrentHumidity() {
    def h = 0.0 as BigDecimal
    //if (settings.appEnabled) {
    h = settings.humiditySensor.currentValue('humidity')
    
    //settings.humiditySensor.
    //statesSince("motion", new Date((long)t0), [max:pastmin*10]
    
    //state.AvgHumidityPastHour = 
/*
        //Simulator is broken and requires this work around for testing.    
        if (settings.humiditySensor.latestState('humidity')) {
            log.debug settings.humiditySensor.latestState('humidity').stringValue[0..-2]
            h = settings.humiditySensor.latestState('humidity').stringValue[0..-2].toBigDecimal()
        } else {
            h = 20
        }        
*/
    //}
    
    //get average of past 1 hour
    
    return h
}

//NOTE: this will return the current humidity if there wre no humidity changed events in the past hour
def getAvgHumidity(minAgo, inPastMin) {
    if (minAgo == 0) {
        minAgo = 10
    }
    if (inPastMin == 0) {
        inPastMin = 240
    }
    def currTime = now()
    
    def recentStates = []

    def oldTime = null
    def recentTime = null

    //if we need to go further back to check the humidity, keep track of how far back we're going
    def nextMinAgo = minAgo

    def c = 0
    while (recentStates.size()==0 && nextMinAgo < 2880) { //keep checking for past 48 hours
        //get average humidity *before the past minAgo minutes, for the past inPastMin minutes
        minAgo = nextMinAgo
        oldTime = new Date((long) currTime - (minAgo * 60 *1000))
        recentTime = new Date((long)currTime - (inPastMin * 60 *1000))
        
        //states between recentTime .. start time
        recentStates = humiditySensor.eventsBetween(oldTime, recentTime, [max:1000]).findResults{ ev ->
            if (ev.getName() =="humidity") {
                return ev
            }
            return null
        }
        nextMinAgo = nextMinAgo + 60
        c = c + 1
    }    

    //go throug the states (oldest first) and see what the humidity was, on average
    def lastTime = 0
    def lastDuration = 0
    def lastHumidity = 0
    def numMinutes = 0
    def sumHumidity = 0
    for (def i = recentStates.size()-1; i>=0; i--)  {
        if (lastTime == 0) {
            lastTime = recentStates[i].date.getTime()
            lastDuration = 0
        }
        else {
            lastDuration = (recentStates[i].date.getTime() - lastTime)/1000/60
        }
        
        numMinutes = numMinutes + lastDuration
        //sumHumidity = sumHumidity + (lastHumidity)*lastDuration //12-28 change
        //instead of assuming the humidity was the previous humidity, assume it was an average of thh current and last humidity
        
        def avg = 0
        if (numMinutes >0 ) {
            avg = sumHumidity/numMinutes
        }
        log.debug "humidity at ${recentStates[i].date.format("h:mm:ss a MM-dd", location.timeZone)}: ${recentStates[i].value} runningavg: ${avg}"
       
        sumHumidity = sumHumidity + ( (lastHumidity+recentStates[i].value.toFloat())/2 )*lastDuration 
        
        //log.debug "Time${recentStates[i].date} Duration ${currentDuration} Humidity ${recentStates[i].value}"
        lastTime = recentStates[i].date.getTime()
        lastHumidity = recentStates[i].value.toFloat()
    }
    //for the last change, use recentTime as the latest time
    lastDuration = (recentTime.getTime() - lastTime)/1000/60
    numMinutes = numMinutes + lastDuration
    sumHumidity = sumHumidity + (lastHumidity)*lastDuration
    
    
    def avgHumidity = 0
    
    if (numMinutes!=0) {
        avgHumidity = sumHumidity/numMinutes
    }
    if (avgHumidity<= 0) {
        avgHumidity = getCurrentHumidity()
    }

    return avgHumidity
}

def checkIfHumidityIsHigh(h) {
    if (humidityActivateLevel == 0) {
        return false
    }

    if (h==0) {
        h = getCurrentHumidity()
    }
    if (h<=0 || h>=100) {
        return false
    }
    return h >= humidityActivateLevel
}

def checkIfHumidityIsLow(h) {
    if (humidityDeactivateLevel == 0) {
        return false
    }
    if (h==0) {
        h = getCurrentHumidity()
    }
    if (h<=0 || h>=100) {
        return false
    }
    return h <= humidityDeactivateLevel
}

def set_fans(fan_state, autoTurnOn = false) {
    state.autoTurnedOn = autoTurnOn

    if (fan_state) {
        //if all fans are not on, then turn them on 
        if (fans.findAll{it.latestValue("switch").toUpperCase() == "ON"}.size()!=fans.size()) {
            setFanCountDown()
        
            send("${app.label} fans On.")
            state.fansOnTime = now()
            if (settings.fanControlEnabled) {
                fans.on()
            } else {
                send("${app.label} fan control is disabled.")
            }
        } else {
            log.debug "${app.label} fans already On."
        }        
    } else {
        if (areFansOn()) {
            send("${app.label} fans Off.")
            state.fansLastRunTime = (now() - state.fansOnTime)

            //BigInteger ms = new java.math.BigInteger(state.fansLastRunTime)
            BigInteger ms = state.fansLastRunTime.toBigInteger()
            int seconds = (BigInteger) (((BigInteger) ms / (1000I)).toBigInteger()                  % 60I)
            int minutes = (BigInteger) (((BigInteger) ms / (1000I * 60I)).toBigInteger()            % 60I)
            int hours   = (BigInteger) (((BigInteger) ms / (1000I * 60I * 60I)).toBigInteger()      % 24I)
            int days    = (BigInteger)  ((BigInteger) ms / (1000I * 60I * 60I * 24I)).toBigInteger()

            def sb = String.format("${app.label} cycle: %d:%02d:%02d:%02d", days, hours, minutes, seconds)
            
            send(sb)

            if (settings.fanControlEnabled) {
                fans.off()
            } else {
                send("${app.label} fan control is disabled.")
            }
            state.fansHoldoff = now()
        } else {
            log.debug "${app.label} fans already Off."
        }
    }

}

private formattedDate() {
    return new Date().format("h:mm:ss a MM-dd", location.timeZone)
}


private send(msg) {

    if (sendPushMessage) {
        sendPush(msg)
    }

    if (phone) {
        sendSms(phone, msg)
    }

    log.debug(msg)
}