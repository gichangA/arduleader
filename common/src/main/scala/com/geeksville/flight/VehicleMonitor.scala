package com.geeksville.flight

import com.geeksville.mavlink.HeartbeatMonitor
import org.mavlink.messages.ardupilotmega._
import org.mavlink.messages.MAVLinkMessage
import com.geeksville.akka.MockAkka
import scala.concurrent.duration._
import scala.language.postfixOps

case object RetryExpired

/**
 * Listens to a particular vehicle, capturing interesting state like heartbeat, cur lat, lng, alt, mode, status and next waypoint
 */
class VehicleMonitor extends HeartbeatMonitor with VehicleSimulator {

  var status: Option[String] = None
  var location: Option[Location] = None
  var batteryPercent: Option[Float] = None

  var waypoints = Seq[msg_mission_item]()

  private var numWaypointsRemaining = 0
  private var nextWaypointToFetch = 0

  override def systemId = 253 // We always claim to be a ground controller (FIXME, find a better way to pick a number)

  protected def onLocationChanged(l: Location) {}
  protected def onStatusChanged(s: String) {}
  protected def onSysStatusChanged() {}
  protected def onWaypointsDownloaded() {}

  private val codeToModeMap = Map(0 -> "MANUAL", 1 -> "CIRCLE", 2 -> "STABILIZE",
    5 -> "FLY_BY_WIRE_A", 6 -> "FLY_BY_WIRE_B", 10 -> "AUTO",
    11 -> "RTL", 12 -> "LOITER", 15 -> "GUIDED", 16 -> "INITIALIZING")

  private val modeToCodeMap = codeToModeMap.map { case (k, v) => (v, k) }

  def currentMode = codeToModeMap.getOrElse(customMode.getOrElse(-1), "unknown")

  /**
   * The mode names we understand
   */
  def modeNames = modeToCodeMap.keys

  private def mReceive: Receiver = {
    case RetryExpired =>
      retryExpired()

    case m: msg_statustext =>
      log.info("Received status: " + m.getText)
      status = Some(m.getText)
      onStatusChanged(m.getText)

    case msg: msg_sys_status =>
      batteryPercent = Some(msg.battery_remaining / 100.0f)
      onSysStatusChanged()

    case msg: msg_global_position_int ⇒
      val loc = VehicleSimulator.decodePosition(msg)
      //log.debug("Received location: " + loc)
      location = Some(loc)
      onLocationChanged(loc)

    //
    // Messages for downloading waypoints from vehicle
    //

    case msg: msg_mission_count =>
      if (msg.target_system == systemId) {
        log.info("Vehicle has %d waypoints, downloading...".format(msg.count))
        checkRetryReply(msg).foreach { msg =>
          // We were just told how many waypoints the target has, now fetch them (one at a time)
          numWaypointsRemaining = msg.count
          nextWaypointToFetch = 0
          waypoints = Seq()
          requestNextWaypoint()
        }
      }

    case msg: msg_mission_item =>
      if (msg.target_system == systemId) {
        log.debug("Receive: " + msg)
        if (msg.seq != nextWaypointToFetch)
          log.error("Ignoring duplicate waypoint response")
        else
          checkRetryReply(msg).foreach { msg =>
            waypoints = waypoints :+ msg

            /*
 * MISSION_ITEM {target_system : 255, target_component : 190, seq : 0, frame : 0, command : 16, current : 1, autocontinue : 1, param1 : 0.0, param2 : 0.0, param3 : 0.0, param4 : 0.0, x : 37.5209159851, y : -122.309059143, z : 143.479995728}
 */
            nextWaypointToFetch += 1
            requestNextWaypoint()
          }
      }

    case msg: msg_mission_ack =>
      if (msg.target_system == systemId) {
        log.debug("Receive: " + msg)
        checkRetryReply(msg)
      }
  }

  override def onHeartbeatFound() {
    super.onHeartbeatFound()

    // First contact, download any waypoints from the vehicle
    startWaypointDownload()
  }

  val numRetries = 5
  var retriesLeft = 0
  val retryInterval = 3000
  var expectedResponse: Option[Class[_]] = None
  var retryPacket: Option[MAVLinkMessage] = None

  /**
   * Send a packet that expects a certain packet type in response, if the response doesn't arrive, then retry
   */
  def sendWithRetry(msg: MAVLinkMessage, expected: Class[_]) {
    expectedResponse = Some(expected)
    retriesLeft = numRetries
    retryPacket = Some(msg)
    MockAkka.scheduler.scheduleOnce(retryInterval milliseconds, this, RetryExpired)
    sendMavlink(msg)
  }

  /**
   * Check to see if this satisfies our retry reply requirement, if it does and it isn't a dup return the message, else None
   */
  def checkRetryReply[T <: MAVLinkMessage](reply: T): Option[T] = {
    expectedResponse.flatMap { e =>
      if (reply.getClass == e) {
        // Success!
        retryPacket = None
        expectedResponse = None
        Some(reply)
      } else
        None
    }
  }

  def retryExpired() {
    retryPacket.foreach { pkt =>
      if (retriesLeft > 0) {
        log.debug("Retry expired on " + pkt + " trying again...")
        retriesLeft -= 1
        sendMavlink(pkt)
        MockAkka.scheduler.scheduleOnce(retryInterval milliseconds, this, RetryExpired)
      } else
        log.error("No more retries, giving up: " + pkt)
    }
  }

  def startWaypointDownload() {
    sendWithRetry(missionRequestList(), classOf[msg_mission_count])
  }

  /**
   * Tell vehicle to select a new mode
   */
  def setMode(mode: String) {
    sendMavlink(setMode(modeToCodeMap(mode)))
  }

  /**
   * FIXME - we currently assume dest has a relative altitude
   */
  def setGuided(dest: Location) = {
    val r = missionItem(0, dest, current = 2)
    sendWithRetry(r, classOf[msg_mission_ack])
    r
  }

  /**
   * FIXME - support timeouts
   */
  private def requestNextWaypoint() {
    if (numWaypointsRemaining > 0) {
      numWaypointsRemaining -= 1
      sendWithRetry(missionRequest(nextWaypointToFetch), classOf[msg_mission_item])
    } else
      onWaypointsDownloaded()
  }

  override def onReceive = mReceive.orElse(super.onReceive)
}