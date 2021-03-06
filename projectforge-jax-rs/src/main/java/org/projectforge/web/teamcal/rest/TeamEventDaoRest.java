/////////////////////////////////////////////////////////////////////////////
//
// Project ProjectForge Community Edition
//         www.projectforge.org
//
// Copyright (C) 2001-2014 Kai Reinhard (k.reinhard@micromata.de)
//
// ProjectForge is dual-licensed.
//
// This community edition is free software; you can redistribute it and/or
// modify it under the terms of the GNU General Public License as published
// by the Free Software Foundation; version 3 of the License.
//
// This community edition is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
// Public License for more details.
//
// You should have received a copy of the GNU General Public License along
// with this program; if not, see http://www.gnu.org/licenses/.
//
/////////////////////////////////////////////////////////////////////////////

package org.projectforge.web.teamcal.rest;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.projectforge.business.converter.DOConverter;
import org.projectforge.business.teamcal.admin.TeamCalCache;
import org.projectforge.business.teamcal.admin.model.TeamCalDO;
import org.projectforge.business.teamcal.event.TeamEventDao;
import org.projectforge.business.teamcal.event.TeamEventFilter;
import org.projectforge.business.teamcal.event.TeamEventService;
import org.projectforge.business.teamcal.event.TeamRecurrenceEvent;
import org.projectforge.business.teamcal.event.ical.HandleMethod;
import org.projectforge.business.teamcal.event.ical.ICalGenerator;
import org.projectforge.business.teamcal.event.ical.ICalHandler;
import org.projectforge.business.teamcal.event.model.ReminderDurationUnit;
import org.projectforge.business.teamcal.event.model.TeamEvent;
import org.projectforge.business.teamcal.event.model.TeamEventDO;
import org.projectforge.common.StringHelper;
import org.projectforge.framework.persistence.user.api.ThreadLocalUserContext;
import org.projectforge.framework.time.DateHolder;
import org.projectforge.framework.time.DayHolder;
import org.projectforge.model.rest.CalendarEventObject;
import org.projectforge.model.rest.RestPaths;
import org.projectforge.rest.JsonUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;

/**
 * REST interface for {@link TeamEventDao}
 *
 * @author Kai Reinhard (k.reinhard@micromata.de)
 */
@Controller
@Path(RestPaths.TEAMEVENTS)
public class TeamEventDaoRest
{
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TeamEventDaoRest.class);

  @Autowired
  private TeamEventService teamEventService;

  @Autowired
  private TeamCalCache teamCalCache;

  /**
   * @param calendarIds  The id's of the calendars to search for events (comma separated). If not given, all calendars
   *                     owned by the context user are assumed.
   * @param daysInFuture Get events from today until daysInFuture (default is 30). Maximum allowed value is 90.
   */
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Response getReminderListPastAndFuture(@QueryParam("calendarIds") final String calendarIds, @QueryParam("daysInPast") final Integer daysInPast,
      @QueryParam("daysInFuture") final Integer daysInFuture)
  {
    log.info("Call rest interface TeamEventDaoRest.getReminderListPastAndFuture - BEGIN");
    Calendar start = null;
    if (daysInPast != null && daysInPast > 0) {
      start = Calendar.getInstance(ThreadLocalUserContext.getTimeZone());
      start.add(Calendar.DAY_OF_MONTH, -daysInPast);
    }
    Calendar end = null;
    if (daysInFuture != null && daysInFuture > 0) {
      end = Calendar.getInstance(ThreadLocalUserContext.getTimeZone());
      end.add(Calendar.DAY_OF_MONTH, daysInFuture);
    }

    final Collection<Integer> cals = getCalendarIds(calendarIds);
    final List<CalendarEventObject> result = new LinkedList<>();
    if (cals.size() > 0) {
      final TeamEventFilter filter = new TeamEventFilter().setTeamCals(cals);
      if (start != null) {
        filter.setStartDate(start.getTime());
      }
      if (end != null) {
        filter.setEndDate(end.getTime());
      }
      final List<TeamEventDO> list = teamEventService.getTeamEventDOList(filter);
      if (list != null && list.size() > 0) {
        list.forEach(event -> result.add(this.getEventObject(event)));
      }
    } else {
      log.warn("No calendar ids are given, so can't find any events.");
    }
    final String json = JsonUtils.toJson(result);
    log.info("Call rest interface TeamEventDaoRest.getReminderList - END");
    return Response.ok(json).build();
  }

  /**
   * Rest call for {@link TeamEventDao#getEventList(TeamEventFilter, boolean)}
   *
   * @param calendarIds  The id's of the calendars to search for events (comma separated). If not given, all calendars
   *                     owned by the context user are assumed.
   * @param daysInFuture Get events from today until daysInFuture (default is 30). Maximum allowed value is 90.
   */
  @GET
  @Path(RestPaths.LIST)
  @Produces(MediaType.APPLICATION_JSON)
  public Response getReminderListFuture(@QueryParam("calendarIds") final String calendarIds,
      @QueryParam("modifiedSince") final Integer daysInFuture)
  {
    log.info("Call rest interface TeamEventDaoRest.getReminderListFuture - BEGIN");
    final DayHolder day = new DayHolder();
    int days = daysInFuture != null ? daysInFuture : 30;
    if (days <= 0 || days > 90) {
      days = 90;
    }
    day.add(Calendar.DAY_OF_YEAR, days);
    final Collection<Integer> cals = getCalendarIds(calendarIds);
    final List<CalendarEventObject> result = new LinkedList<>();
    if (cals.size() > 0) {
      final Date now = new Date();
      final TeamEventFilter filter = new TeamEventFilter().setStartDate(now).setEndDate(day.getDate()).setTeamCals(cals);
      final List<TeamEvent> list = teamEventService.getEventList(filter, true);
      if (list != null && list.size() > 0) {
        for (final TeamEvent event : list) {
          if (event.getStartDate().after(now) == true) {
            result.add(this.getEventObject(event));
          } else {
            log.info("Start date not in future:" + event.getStartDate() + ", " + event.getSubject());
          }
        }
      }
    } else {
      log.warn("No calendar ids are given, so can't find any events.");
    }
    final String json = JsonUtils.toJson(result);
    log.info("Call rest interface TeamEventDaoRest.getReminderListFuture - END");
    return Response.ok(json).build();
  }

  @PUT
  @Path(RestPaths.SAVE)
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response saveTeamEvent(final CalendarEventObject calendarEvent)
  {
    log.info("Call rest interface TeamEventDaoRest.saveTeamEvent");
    return handleICal(calendarEvent);
  }

  @PUT
  @Path(RestPaths.UPDATE)
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  public Response updateTeamEvent(final CalendarEventObject calendarEvent)
  {
    log.info("Call rest interface TeamEventDaoRest.updateTeamEvent");
    return this.handleICal(calendarEvent);
  }

  private Response handleICal(final CalendarEventObject calendarEvent)
  {
    try {
      //Getting the calender at which the event will created/updated
      TeamCalDO teamCalDO = teamCalCache.getCalendar(calendarEvent.getCalendarId());

      // Get handler
      final ICalHandler handler = this.teamEventService.getEventHandler(teamCalDO);
      final InputStream iCalStream = new ByteArrayInputStream(Base64.decodeBase64(calendarEvent.getIcsData()));

      if (handler.readICal(iCalStream, HandleMethod.ADD_UPDATE) == false || handler.isEmpty()) {
        return Response.serverError().build();
      }

      if (handler.validate() == false) {
        return Response.serverError().build();
      }

      handler.persist(true);

      final CalendarEventObject result = this.getEventObject(handler.getFirstResult());
      log.info("Team event: " + result.getSubject() + " for calendar #" + teamCalDO.getId() + " successfully created.");

      final String json = JsonUtils.toJson(result);
      return Response.ok(json).build();
    } catch (Exception e) {
      log.error("Exception while creating team event", e);
      return Response.serverError().build();
    }
  }

  @DELETE
  @Consumes(MediaType.APPLICATION_JSON)
  public Response deleteTeamEvent(final CalendarEventObject calendarEvent)
  {
    //Getting the calender at which the event will created/updated
    TeamCalDO teamCalDO = teamCalCache.getCalendar(calendarEvent.getCalendarId());

    // Get handler
    final ICalHandler handler = this.teamEventService.getEventHandler(teamCalDO);
    final InputStream iCalStream = new ByteArrayInputStream(Base64.decodeBase64(calendarEvent.getIcsData()));

    if (handler.readICal(iCalStream, HandleMethod.CANCEL) == false || handler.eventCount() != 1) {
      return Response.serverError().build();
    }

    if (handler.validate() == false) {
      return Response.serverError().build();
    }

    handler.persist(true);

    return Response.ok().build();
  }

  private CalendarEventObject getEventObject(final TeamEvent src)
  {
    if (src == null) {
      return null;
    }

    final ICalGenerator generator = ICalGenerator.exportAllFields();
    generator.editableVEvent(true);

    final CalendarEventObject event = new CalendarEventObject();
    event.setUid(src.getUid());
    event.setStartDate(src.getStartDate());
    event.setEndDate(src.getEndDate());
    event.setLocation(src.getLocation());
    event.setNote(src.getNote());
    event.setSubject(src.getSubject());

    if (src instanceof TeamEventDO) {
      copyFields(event, (TeamEventDO) src);
      generator.addEvent((TeamEventDO) src);
      event.setIcsData(Base64.encodeBase64String(generator.getCalendarAsByteStream().toByteArray()));

      return event;
    }

    if (src instanceof TeamRecurrenceEvent) {
      final TeamEventDO master = ((TeamRecurrenceEvent) src).getMaster();
      if (master != null) {
        copyFields(event, master);
      }
    }

    TeamEventDO eventDO = new TeamEventDO();

    eventDO.setEndDate(new Timestamp(src.getEndDate().getTime()));
    eventDO.setLocation(src.getLocation());
    eventDO.setNote(src.getNote());
    eventDO.setStartDate(new Timestamp(src.getStartDate().getTime()));
    eventDO.setSubject(src.getSubject());
    eventDO.setUid(src.getUid());
    eventDO.setAllDay(src.isAllDay());

    generator.addEvent(eventDO);

    event.setIcsData(Base64.encodeBase64String(generator.getCalendarAsByteStream().toByteArray()));

    return event;
  }

  private void copyFields(final CalendarEventObject event, final TeamEventDO src)
  {
    event.setCalendarId(src.getCalendarId());
    event.setRecurrenceRule(src.getRecurrenceRule());
    event.setRecurrenceExDate(src.getRecurrenceExDate());
    event.setRecurrenceUntil(src.getRecurrenceUntil());
    DOConverter.copyFields(event, src);
    event.setLastUpdate(src.getDtStamp());
    if (src.getReminderActionType() != null && src.getReminderDuration() != null
        && src.getReminderDurationUnit() != null) {
      event.setReminderType(src.getReminderActionType().toString());
      event.setReminderDuration(src.getReminderDuration());
      final ReminderDurationUnit unit = src.getReminderDurationUnit();
      event.setReminderUnit(unit.toString());
      final DateHolder date = new DateHolder(src.getStartDate());
      if (unit == ReminderDurationUnit.MINUTES) {
        date.add(Calendar.MINUTE, -src.getReminderDuration());
        event.setReminder(date.getDate());
      } else if (unit == ReminderDurationUnit.HOURS) {
        date.add(Calendar.HOUR, -src.getReminderDuration());
        event.setReminder(date.getDate());
      } else if (unit == ReminderDurationUnit.DAYS) {
        date.add(Calendar.DAY_OF_YEAR, -src.getReminderDuration());
        event.setReminder(date.getDate());
      } else {
        log.warn("ReminderDurationUnit '" + src.getReminderDurationUnit() + "' not yet implemented.");
      }
    }
  }

  private Collection<Integer> getCalendarIds(String calendarIds)
  {
    final Collection<Integer> cals = new LinkedList<>();
    if (StringUtils.isBlank(calendarIds) == true) {
      final Collection<TeamCalDO> ownCals = teamCalCache.getAllOwnCalendars();
      if (ownCals != null && ownCals.size() > 0) {
        for (final TeamCalDO cal : ownCals) {
          cals.add(cal.getId());
        }
      }
    } else {
      final Integer[] ids = StringHelper.splitToIntegers(calendarIds, ",;:");
      if (ids != null && ids.length > 0) {
        for (final Integer id : ids) {
          if (id != null) {
            cals.add(id);
          }
        }
      }
    }
    return cals;
  }

}
