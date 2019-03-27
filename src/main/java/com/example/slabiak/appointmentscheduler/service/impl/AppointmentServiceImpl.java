package com.example.slabiak.appointmentscheduler.service.impl;

import com.example.slabiak.appointmentscheduler.dao.AppointmentRepository;
import com.example.slabiak.appointmentscheduler.dao.ChatMessageRepository;
import com.example.slabiak.appointmentscheduler.entity.Appointment;
import com.example.slabiak.appointmentscheduler.entity.ChatMessage;
import com.example.slabiak.appointmentscheduler.entity.Work;
import com.example.slabiak.appointmentscheduler.entity.WorkingPlan;
import com.example.slabiak.appointmentscheduler.entity.user.User;
import com.example.slabiak.appointmentscheduler.entity.user.provider.Provider;
import com.example.slabiak.appointmentscheduler.model.DayPlan;
import com.example.slabiak.appointmentscheduler.model.TimePeroid;
import com.example.slabiak.appointmentscheduler.service.AppointmentService;
import com.example.slabiak.appointmentscheduler.service.EmailService;
import com.example.slabiak.appointmentscheduler.service.UserService;
import com.example.slabiak.appointmentscheduler.service.WorkService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
public class AppointmentServiceImpl implements AppointmentService {

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private WorkService workService;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    EmailService emailService;

    @Autowired
    JwtTokenServiceImpl jwtTokenService;


    public AppointmentServiceImpl() {
    }


    @Override
    public void updateAppointment(Appointment appointment) {
        appointmentRepository.save(appointment);
    }

    @Override
    public Appointment getAppointmentById(int id) {
        Optional<Appointment> result = appointmentRepository.findById(id);
        Appointment appointment = null;

        if (result.isPresent()) {
            appointment = result.get();
        }
        else {
            // todo throw new excep
        }

        return appointment;
    }

    @Override
    public List<Appointment> getAllAppointments() {
        return appointmentRepository.findAll();
    }

    @Override
    public void deleteAppointmentById(int id) {
        appointmentRepository.deleteById(id);
    }

    @Override
    public List<Appointment> getAppointmentByCustomerId(int customerId) {
        return appointmentRepository.findByCustomerId(customerId);
    }

    @Override
    public List<Appointment> getAppointmentByProviderId(int providerId) {
        return appointmentRepository.findByProviderId(providerId);
    }

    @Override
    public List<Appointment> getAppointmentsByProviderAtDay(int providerId, LocalDate day) {
        return appointmentRepository.findByProviderIdWithStartInPeroid(providerId,day.atStartOfDay(), day.atStartOfDay().plusHours(24));
    }

    @Override
    public List<TimePeroid> getAvailableTimePeroidsForProvider(int providerId, int workId, LocalDate date){
        Provider p = userService.getProviderById(providerId);
       WorkingPlan workingPlan = p.getWorkingPlan();
        DayPlan selectedDay = workingPlan.getDay(date.getDayOfWeek().toString().toLowerCase());

        List<Appointment> providerAppointments = getAppointmentsByProviderAtDay(p.getId(),date);

        List<TimePeroid> availablePeroids = new ArrayList<TimePeroid>();
        // get peroids from working hours for selected day excluding breaks

        availablePeroids = selectedDay.getTimePeroidsWithBreaksExcluded();
        // exclude provider's appointments from available peroids
        availablePeroids = excludeAppointmentsFromTimePeroids(availablePeroids,providerAppointments);
       return calculateAvailableHours(availablePeroids,workService.getWorkById(workId));
       // return availablePeroids;
    }

    @Override
    public void createNewAppointment(int workId, int providerId, int customerId, LocalDateTime start) {
        Appointment appointment = new Appointment();
        appointment.setStatus("scheduled");
        appointment.setCustomer(userService.getCustomerById(customerId));
        appointment.setProvider(userService.getProviderById(providerId));
        Work work = workService.getWorkById(workId);
        appointment.setWork(work);
        appointment.setStart(start);
        appointment.setEnd(start.plusMinutes(work.getDuration()));
        appointmentRepository.save(appointment);
        emailService.sendNewAppointmentScheduledNotification(appointment);
    }

    @Override
    public void addMessageToAppointmentChat(int appointmentId, int authorId, ChatMessage chatMessage) {
        chatMessage.setAuthor(userService.getUserById(authorId));
        chatMessage.setAppointment(getAppointmentById(appointmentId));
        chatMessage.setCreatedAt(LocalDateTime.now());
        chatMessageRepository.save(chatMessage);
    }



    @Override
    public List<TimePeroid> calculateAvailableHours(List<TimePeroid> availableTimePeroids, Work work){
        ArrayList<TimePeroid> availableHours = new ArrayList<TimePeroid>();
        for(TimePeroid peroid: availableTimePeroids){
            TimePeroid workPeroid = new TimePeroid(peroid.getStart(),peroid.getStart().plusMinutes(work.getDuration()));
            while(workPeroid.getEnd().isBefore(peroid.getEnd()) || workPeroid.getEnd().equals(peroid.getEnd())){
                availableHours.add(new TimePeroid(workPeroid.getStart(),workPeroid.getStart().plusMinutes(work.getDuration())));
                workPeroid.setStart(workPeroid.getStart().plusMinutes(work.getDuration()));
                workPeroid.setEnd(workPeroid.getEnd().plusMinutes(work.getDuration()));
            }
        }
        return availableHours;
    }

    @Override
    public List<TimePeroid> excludeAppointmentsFromTimePeroids(List<TimePeroid> peroids, List<Appointment> appointments){

            List<TimePeroid> toAdd = new ArrayList<TimePeroid>();
            Collections.sort(appointments);
            for(Appointment appointment: appointments){
                for(TimePeroid peroid:peroids){
                    if((appointment.getStart().toLocalTime().isBefore(peroid.getStart()) || appointment.getStart().toLocalTime().equals(peroid.getStart())) && appointment.getEnd().toLocalTime().isAfter(peroid.getStart()) && appointment.getEnd().toLocalTime().isBefore(peroid.getEnd())){
                        peroid.setStart(appointment.getEnd().toLocalTime());
                    }
                    if(appointment.getStart().toLocalTime().isAfter(peroid.getStart())&& appointment.getStart().toLocalTime().isBefore(peroid.getEnd()) && appointment.getEnd().toLocalTime().isAfter(peroid.getEnd()) || appointment.getEnd().toLocalTime().equals(peroid.getEnd())){
                        peroid.setEnd(appointment.getStart().toLocalTime());
                    }
                    if(appointment.getStart().toLocalTime().isAfter(peroid.getStart()) && appointment.getEnd().toLocalTime().isBefore(peroid.getEnd())){
                        toAdd.add(new TimePeroid(peroid.getStart(),appointment.getStart().toLocalTime()));
                        peroid.setStart(appointment.getEnd().toLocalTime());
                    }
                }
            }
            peroids.addAll(toAdd);
            Collections.sort(peroids);
        return peroids;
    }

    @Override
    public List<Appointment> getCanceledAppointmentsByCustomerIdForCurrentMonth(int customerId){
        return appointmentRepository.findByCustomerIdCanceledAfterDate(customerId, LocalDate.now().with(TemporalAdjusters.firstDayOfMonth()).atStartOfDay());
    }

    @Override
    public void updateUserAppointmentsStatuses(int userId) {
         /*
         * find appointments which requires status change from scheudled to finished and change their status
         * (all appointments which have status 'scheduled' and their end date is before current timestamp)
         * */
        for(Appointment appointment: appointmentRepository.findScheduledByUserIdWithEndBeforeDate(LocalDateTime.now(),userId)){
            appointment.setStatus("finished");
            updateAppointment(appointment);
        }
         /*
         * find appointments which requires status change from finished to confirmed and change their status
         * (all appointments which have status 'finished' and their end date is more than 24 hours before current timestamp)
         * */
        for(Appointment appointment: appointmentRepository.findFinishedByUserIdWithEndBeforeDate(LocalDateTime.now().minusHours(24),userId)){

            appointment.setStatus("invoiced");
            updateAppointment(appointment);
        }
    }

    @Override
    public void updateAllAppointmentsStatuses() {
         /*
         * find appointments which requires status change from scheudled to finished and change their status
         * (all appointments which have status 'scheduled' and their end date is before current timestamp)
         * */
        for(Appointment appointment: appointmentRepository.findScheduledWithEndBeforeDate(LocalDateTime.now())){
            appointment.setStatus("finished");
            updateAppointment(appointment);
            /*
            * user have 24h after appointment finished to deny that appointment took place
            * if it's less than 24h since the appointment finished, send him a link with a token that allows to deny that appointment took place
            * it it's more than 24h, dont send it cause it's to late to deny
            * */
            if(LocalDateTime.now().minusHours(24).isBefore(appointment.getEnd())) {
                emailService.sendAppointmentFinishedNotification(appointment);
            }
        }
         /*
         * find appointments which requires status change from finished to confirmed and change their status
         * (all appointments which have status 'finished' and their end date is more than 24 hours before current timestamp)
         * */
        for(Appointment appointment: appointmentRepository.findFinishedWithEndBeforeDate(LocalDateTime.now().minusHours(24))){
            appointment.setStatus("confirmed");
            updateAppointment(appointment);
        }
    }


    @Override
    public void cancelUserAppointmentById(int appointmentId, int userId) {
        Appointment appointment = appointmentRepository.getOne(appointmentId);
        appointment.setStatus("canceled");
        User canceler = userService.getUserById(userId);
        appointment.setCanceler(canceler);
        appointment.setCanceledAt(LocalDateTime.now());
        appointmentRepository.save(appointment);
        if(canceler.equals(appointment.getCustomer())){
            emailService.sendAppointmentCanceledByCustomerNotification(appointment);
        } else if(canceler.equals(appointment.getProvider())){
            emailService.sendAppointmentCanceledByProviderNotification(appointment);
        }

    }


    @Override
    public boolean isCustomerAllowedToRejectAppointment(int userId, int appointmentId) {
        User user = userService.getUserById(userId);
        Appointment appointment = getAppointmentById(appointmentId);

        if(!appointment.getCustomer().equals(user)){
            return false;
        } else if(!appointment.getStatus().equals("finished")){
            return false;
        } else if(LocalDateTime.now().isAfter(appointment.getEnd().plusHours(24))){
            return false;
        }else{
            return true;
        }
    }

    @Override
    public boolean requestAppointmentRejection(int appointmentId, int customerId) {
        if(isCustomerAllowedToRejectAppointment(customerId,appointmentId)){
            Appointment appointment = getAppointmentById(appointmentId);
            appointment.setStatus("rejection requested");
            emailService.sendAppointmentRejectionRequestedNotification(appointment);
            updateAppointment(appointment);
            return true;
        } else{
            return false;
        }

    }


    @Override
    public boolean requestAppointmentRejection(String token) {
        if(jwtTokenService.validateToken(token)){
            int appointmentId = jwtTokenService.getAppointmentIdFromToken(token);
            int customerId = jwtTokenService.getCustomerIdFromToken(token);
            return requestAppointmentRejection(appointmentId,customerId);
        }
        return false;
    }




    @Override
    public boolean isProviderAllowedToAcceptRejection(int providerId, int appointmentId) {
        User user = userService.getUserById(providerId);
        Appointment appointment = getAppointmentById(appointmentId);

        if(!appointment.getProvider().equals(user)){
            return false;
        } else if(!appointment.getStatus().equals("rejection requested")){
            return false;
        } else{
            return true;
        }
    }

    @Override
    public boolean acceptRejection(int appointmentId, int customerId) {
        if(isProviderAllowedToAcceptRejection(customerId,appointmentId)){
            Appointment appointment = getAppointmentById(appointmentId);
            appointment.setStatus("rejected");
            updateAppointment(appointment);
            emailService.sendAppointmentRejectionAcceptedNotification(appointment);
            return true;
        } else{
            return false;
        }
    }

    @Override
    public boolean acceptRejection(String token) {
        if(jwtTokenService.validateToken(token)){
            int appointmentId = jwtTokenService.getAppointmentIdFromToken(token);
            int providerId = jwtTokenService.getProviderIdFromToken(token);
            return acceptRejection(appointmentId,providerId);
        }
        return false;
    }

    @Override
    public String getCancelNotAllowedReason(int userId, int appointmentId){
        User user = userService.getUserById(userId);
        Appointment appointment = getAppointmentById(appointmentId);

        // conditions for provider
        if (appointment.getProvider().equals(user) || user.hasRole("ROLE_ADMIN")) {
            if (!appointment.getStatus().equals("scheduled")) {
                return "Only appoinmtents with scheduled status can be cancelled.";
            } else {
                return null;
            }
        }

        // conditions for provider
        if (appointment.getCustomer().equals(user)) {
            if (!appointment.getStatus().equals("scheduled")) {
                return "Only appoinmtents with scheduled status can be cancelled.";
            } else if (LocalDateTime.now().plusHours(24).isAfter(appointment.getStart())) {
                return "Appointments which will be in less than 24 hours cannot be canceled.";
            } else if (!appointment.getWork().getEditable()) {
                return "This type of appointment can be canceled only by Provider.";
            } else if (getCanceledAppointmentsByCustomerIdForCurrentMonth(userId).size() >= 1) {
                return "You can't cancel this appointment because you exceeded maximum number of cancellations in this month.";
            } else {
                return null;
            }
        }
        return null;
    }

    @Override
    public int getNumberOfCanceledAppointmentsForUser(int userId) {
        return appointmentRepository.findCanceledByUser(userId).size();
    }

    @Override
    public int getNumberOfScheduledAppointmentsForUser(int userId) {
        return appointmentRepository.findScheduledByUserId(userId).size();
    }

    @Override
    public List<Appointment> getConfirmedAppointmentsByCustomerId(int customerId) {
        return appointmentRepository.findConfirmedByCustomerId(customerId);
    }
}