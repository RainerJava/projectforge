package org.projectforge.business.teamcal.event.ical;

import static org.projectforge.business.teamcal.event.ical.ICalConverterStore.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import org.projectforge.business.teamcal.event.model.TeamEventDO;
import org.projectforge.framework.calendar.CalendarUtils;
import org.projectforge.framework.persistence.user.api.ThreadLocalUserContext;
import org.projectforge.framework.persistence.user.entities.PFUserDO;

import net.fortuna.ical4j.data.CalendarOutputter;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.CalScale;
import net.fortuna.ical4j.model.property.DtEnd;
import net.fortuna.ical4j.model.property.DtStart;
import net.fortuna.ical4j.model.property.Method;
import net.fortuna.ical4j.model.property.ProdId;
import net.fortuna.ical4j.model.property.Summary;
import net.fortuna.ical4j.model.property.Uid;
import net.fortuna.ical4j.model.property.Version;

public class ICalGenerator
{
  private static final org.apache.log4j.Logger log = org.apache.log4j.Logger.getLogger(ICalGenerator.class);

  //------------------------------------------------------------------------------------------------------------
  // Static part
  //------------------------------------------------------------------------------------------------------------

  public static ICalGenerator exportAllFields()
  {
    final ICalGenerator generator = new ICalGenerator();
    generator.exportsVEvent = new ArrayList<>(FULL_LIST);

    return generator;
  }

  public static ICalGenerator forMethod(final Method method)
  {
    final ICalGenerator generator;

    if (Method.REQUEST.equals(method)) {
      generator = exportAllFields();
    } else if (Method.CANCEL.equals(method)) {
      generator = new ICalGenerator();
      generator.exportsVEvent = new ArrayList<>(Arrays.asList(VEVENT_UID, VEVENT_DTSTAMP, VEVENT_DTSTART, VEVENT_SEQUENCE, VEVENT_ORGANIZER,
          VEVENT_ATTENDEES, VEVENT_RRULE, VEVENT_EX_DATE));
    } else {
      throw new UnsupportedOperationException(""); // TODO
    }

    generator.method = method;
    return generator;
  }

  //------------------------------------------------------------------------------------------------------------
  // None static part
  //------------------------------------------------------------------------------------------------------------

  private List<String> exportsVEvent;
  private Calendar calendar;
  private PFUserDO user;
  private Locale locale;
  private TimeZone timeZone;
  private Method method;

  private ICalGenerator()
  {
    this.exportsVEvent = new ArrayList<>();

    // set user, timezone, locale
    this.user = ThreadLocalUserContext.getUser();
    this.timeZone = ThreadLocalUserContext.getTimeZone();
    this.locale = ThreadLocalUserContext.getLocale();

    this.reset();
  }

  public ICalGenerator setContext(final PFUserDO user, final TimeZone timeZone, final Locale locale)
  {
    this.user = user;
    this.timeZone = timeZone;
    this.locale = locale;

    return this;
  }

  public ICalGenerator reset()
  {
    // creating a new calendar
    this.calendar = new Calendar();
    calendar.getProperties().add(new ProdId("-//" + user.getDisplayUsername() + "//ProjectForge//" + locale.toString().toUpperCase()));
    calendar.getProperties().add(Version.VERSION_2_0);
    calendar.getProperties().add(CalScale.GREGORIAN);

    // set time zone
    if (this.timeZone != null) {
      final net.fortuna.ical4j.model.TimeZone timezone = TIMEZONE_REGISTRY.getTimeZone(this.timeZone.getID());
      calendar.getComponents().add(timezone.getVTimeZone());
    }

    // set method
    if (this.method != null) {
      calendar.getProperties().add(method);
    }

    return this;
  }

  public Calendar getCalendar()
  {
    return this.calendar;
  }

  public ByteArrayOutputStream getCalendarAsByteStream()
  {
    try {
      ByteArrayOutputStream stream = new ByteArrayOutputStream();
      CalendarOutputter outputter = new CalendarOutputter();

      outputter.output(this.calendar, stream);

      return stream;
    } catch (IOException e) {
      log.error("Error while exporting calendar " + e.getMessage());
      return null;
    }
  }

  public void writeCalendarToOutputStream(final OutputStream stream)
  {
    try {
      CalendarOutputter outputter = new CalendarOutputter();
      outputter.output(this.calendar, stream);
    } catch (IOException e) {
      log.error("Error while exporting calendar " + e.getMessage());
    }
  }

  public boolean isEmpty()
  {
    return this.calendar.getComponents(Component.VEVENT).isEmpty();
  }

  public ICalGenerator addEvent(final TeamEventDO event)
  {
    final VEvent vEvent = this.convertVEvent(event);

    if (vEvent != null) {
      this.calendar.getComponents().add(vEvent);
    }

    return this;
  }

  public ICalGenerator addEvent(final VEvent vEvent)
  {
    this.calendar.getComponents().add(vEvent);

    return this;
  }

  public ICalGenerator addEvent(final Date startDate, final Date endDate, final boolean allDay, final String summary, final String uid)
  {
    this.calendar.getComponents().add(this.convertVEvent(startDate, endDate, allDay, summary, uid));

    return this;
  }

  public VEvent convertVEvent(final TeamEventDO event)
  {
    final ICalConverterStore store = ICalConverterStore.getInstance();

    // create vEvent
    final VEvent vEvent = new VEvent(false);

    // set time zone
    if (this.timeZone != null) {
      final net.fortuna.ical4j.model.TimeZone timezone = TIMEZONE_REGISTRY.getTimeZone(this.timeZone.getID());
      vEvent.getProperties().add(timezone.getVTimeZone().getTimeZoneId());
    }

    for (String export : this.exportsVEvent) {
      VEventComponentConverter converter = store.getVEventConverter(export);

      if (converter == null) {
        // TODO
        throw new RuntimeException("Unknown converter " + export);
      }

      converter.toVEvent(event, vEvent);
    }

    return vEvent;
  }

  public VEvent convertVEvent(final Date startDate, final Date endDate, final boolean allDay, final String summary, final String uid)
  {
    VEvent vEvent = new VEvent(false);
    final net.fortuna.ical4j.model.TimeZone timezone = TIMEZONE_REGISTRY.getTimeZone(timeZone.getID());
    final net.fortuna.ical4j.model.Date fortunaStartDate, fortunaEndDate;

    if (allDay == true) {
      final Date startUtc = CalendarUtils.getUTCMidnightDate(startDate);
      final Date endUtc = CalendarUtils.getUTCMidnightDate(endDate);
      fortunaStartDate = new net.fortuna.ical4j.model.Date(startUtc);
      // TODO should not be done
      final org.joda.time.DateTime jodaTime = new org.joda.time.DateTime(endUtc);
      // requires plus 1 because one day will be omitted by calendar.
      fortunaEndDate = new net.fortuna.ical4j.model.Date(jodaTime.plusDays(1).toDate());
    } else {
      fortunaStartDate = new net.fortuna.ical4j.model.DateTime(startDate);
      ((net.fortuna.ical4j.model.DateTime) fortunaStartDate).setTimeZone(timezone);
      fortunaEndDate = new net.fortuna.ical4j.model.DateTime(endDate);
      ((net.fortuna.ical4j.model.DateTime) fortunaEndDate).setTimeZone(timezone);
    }

    vEvent.getProperties().add(timezone.getVTimeZone().getTimeZoneId());
    vEvent.getProperties().add(new DtStart(fortunaStartDate));
    vEvent.getProperties().add(new DtEnd(fortunaEndDate));

    vEvent.getProperties().add(new Summary(summary));
    vEvent.getProperties().add(new Uid(uid));

    return vEvent;
  }

  public ICalGenerator editableVEvent(boolean value)
  {
    exportVEventProperty(VEVENT_ORGANIZER_EDITABLE, value);
    exportVEventProperty(VEVENT_ORGANIZER, !value);
    return this;
  }

  public ICalGenerator exportVEventAlarm(boolean value)
  {
    return exportVEventProperty(VEVENT_ALARM, value);
  }

  public ICalGenerator exportVEventAttendees(boolean value)
  {
    return exportVEventProperty(VEVENT_ATTENDEES, value);
  }

  public ICalGenerator doExportVEventProperty(String value)
  {
    return exportVEventProperty(value, true);
  }

  public ICalGenerator doNotExportVEventProperty(String value)
  {
    return exportVEventProperty(value, false);
  }

  public ICalGenerator exportVEventProperty(String value, boolean export)
  {
    if (export) {
      if (this.exportsVEvent.contains(value) == false) {
        this.exportsVEvent.add(value);
      }
    } else {
      if (this.exportsVEvent.contains(value)) {
        this.exportsVEvent.remove(value);
      }
    }

    return this;
  }

  public List<String> getExportsVEvent()
  {
    return this.exportsVEvent;
  }
}
