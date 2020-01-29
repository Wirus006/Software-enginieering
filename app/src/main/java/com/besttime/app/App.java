package com.besttime.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;

import com.besttime.app.helpers.ContactImporter;
import com.besttime.app.helpers.WhatsappCallPerformable;
import com.besttime.app.helpers.WhatsappRedirector;
import com.besttime.json.Json;
import com.besttime.models.Contact;
import com.besttime.ui.helpers.WhatsappContactIdRetriever;
import com.besttime.ui.viewModels.ContactEntryWithWhatsappId;
import com.besttime.workhorse.AvailType;
import com.besttime.workhorse.CurrentTime;
import com.besttime.workhorse.DayOfTheWeek;
import com.besttime.workhorse.Form;
import com.besttime.workhorse.FormManager;
import com.besttime.workhorse.Hours;
import com.besttime.workhorse.QueriesType;
import com.besttime.workhorse.Query;
import com.besttime.workhorse.QuerySmsComputation;
import com.besttime.workhorse.SmsManager;
import com.besttime.workhorse.Week;
import com.besttime.workhorse.helpers.ContactEntryComparator;

import java.io.IOException;
import java.io.Serializable;
import java.security.GeneralSecurityException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

public class App implements Serializable, WhatsappCallPerformable, ContactsListSortable {

    public final static String nameToDeserialize = "app";

    public static final int PERMISSIONS_REQUEST_SEND_SMS = 121;
    public static final int PERMISSIONS_REQUEST_READ_CONTACTS = 199;
    public static final int PERMISSIONS_PHONE_CALL = 197;

    public static final int WHATSAPP_VIDEO_CALL_REQUEST = 11;


    private boolean firstUse;
    private List<String> contactListJsonNames;
    private transient List<ContactEntry> contactEntries = new ArrayList<>();
    private Date lastLaunch;
    private transient FormManager formManager;
    private transient Json json;

    private transient Context androidContext;

    private transient ContactImporter contactImporter;
    private transient WhatsappRedirector whatsappRedirector;
    private transient WhatsappContactIdRetriever whatsappContactIdRetriever;

    private transient boolean doingCall;

    private transient ContactsWithColorsContainer contactsWithColorsContainer;


    /**
     *
     * @param androidContext
     * @param contactsWithColorsContainer
     * @throws IOException
     * @throws GeneralSecurityException When there was error creating formManager
     */
    public App(final Context androidContext,
               @Nullable ContactsWithColorsContainer contactsWithColorsContainer) throws IOException, GeneralSecurityException, ParseException, ClassNotFoundException {
        this.androidContext = androidContext;
        this.contactsWithColorsContainer = contactsWithColorsContainer;
        contactListJsonNames = new ArrayList<>();

        json = new Json(androidContext);

        firstUse = false;

        setAllTransientFields(androidContext, contactsWithColorsContainer);

    }

    public void importContacts() throws ClassNotFoundException, IOException {
        List<Contact> importedContacts = contactImporter.getAllContacts();


        if(contactEntries != null){
            contactEntries.clear();

        }


        // Check if something was deleted
        int jsonNamesSize = contactListJsonNames.size();
        for (int i = 0; i < jsonNamesSize; i++) {
            String jsonName = contactListJsonNames.get(i);
            String[] contactInfoFromJsonName = ContactEntry.getContactInfoFromJsonName(jsonName);
            int contactIdFromJsonName = Integer.parseInt(contactInfoFromJsonName[0]);
            String contactPhoneNumFromJsonName = contactInfoFromJsonName[1];

            boolean wasOnImportedContactsList = false;

            for (Contact importedContact :
                    importedContacts) {

                if(importedContact.getId() == contactIdFromJsonName
                        && importedContact.getPhoneNumber().equals(contactPhoneNumFromJsonName)){
                    wasOnImportedContactsList = true;
                    break;
                }

            }
            if(!wasOnImportedContactsList){
                contactListJsonNames.remove(i);
                jsonNamesSize --;
                i --;
            }


        }
        json.serialize(App.nameToDeserialize, this);





        for (Contact contact :
                importedContacts) {
            ContactEntry newContactEntry = new ContactEntry(contact);
            String nameToSerialize = newContactEntry.generateNameForJson();
            if(!contactListJsonNames.contains(nameToSerialize)){
                contactListJsonNames.add(nameToSerialize);
                json.serialize(nameToSerialize, newContactEntry);
            }
            // Check if contact name has changed ...
            else{
                ContactEntry storedContact = deserializeContact(nameToSerialize);
                if(!storedContact.getContactName().equals(newContactEntry.getContactName())){
                    storedContact.changeContactName(newContactEntry.getContactName());
                    json.serialize(nameToSerialize, storedContact);
                }
            }
        }

        json.serialize(App.nameToDeserialize, this);

        contactEntries = deserializeAllContacts();
    }

    private ContactEntry deserializeContact(String nameToSerialize) throws IOException, ClassNotFoundException {

        ContactEntry storedContact = null;
        try {
            storedContact = (ContactEntry) json.deserialize(nameToSerialize);
        } catch (IOException e) {
            e.printStackTrace();
            throw e;
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
            throw e;
        }

        return storedContact;
    }

    /**
     * Sets formManager if it has been null, does nothing otherwise.
     * @param formManager
     */
    public void setFormManager(FormManager formManager) {
        if(formManager == null){
            this.formManager = formManager;
        }
    }


    public void setAllTransientFields(Context androidContext,
                                      @Nullable ContactsWithColorsContainer contactsWithColorsContainer) throws IOException, GeneralSecurityException, ParseException, ClassNotFoundException {
        this.androidContext = androidContext;
        this.contactsWithColorsContainer = contactsWithColorsContainer;
        json = new Json(androidContext);
        contactImporter = new ContactImporter(PERMISSIONS_REQUEST_READ_CONTACTS, androidContext);
        whatsappContactIdRetriever =new WhatsappContactIdRetriever(androidContext.getContentResolver());
        whatsappRedirector = new WhatsappRedirector(androidContext, whatsappContactIdRetriever);

        try {
            formManager = (FormManager) json.deserialize(FormManager.JSON_NAME);
        }
        catch (IOException e){
            formManager = new FormManager();
        }

        formManager.setTransientFields(new SmsManager(androidContext, PERMISSIONS_REQUEST_SEND_SMS));

        contactEntries = deserializeAllContacts();


        formManager.checkResponses(contactEntries);

        for (ContactEntry contactEntry :
                contactEntries) {
            json.serialize(contactEntry.generateNameForJson(), contactEntry);
        }

        json.serialize(FormManager.JSON_NAME, formManager);

        formManager.sendRemindersThatFormCanBeUpdated();

    }

    public List<ContactEntry> getListOfContactsCallableByWhatsapp(){

        if(whatsappContactIdRetriever != null && contactEntries != null){
            ArrayList<ContactEntry> contactEntriesWithWhatsappId = new ArrayList<>();
            for (ContactEntry contactEntry :
                    contactEntries) {
                ContactEntryWithWhatsappId contactEntryWithWhatsappId = new ContactEntryWithWhatsappId(contactEntry, whatsappContactIdRetriever);
                if(contactEntryWithWhatsappId.getWhatsappVideCallId() >= 0){
                    contactEntriesWithWhatsappId.add(contactEntry);
                }
            }

            return contactEntriesWithWhatsappId;
        }

        return null;

    }


    public boolean sendReminderToContact(ContactEntry contactToSendTo){

        return formManager.sendReminderThatFormCanBeUpdated(contactToSendTo);
    }


    public List<ContactEntry> getContactList() {
        return contactEntries;
    }

    private List<ContactEntry> deserializeAllContacts() throws IOException, ClassNotFoundException {
        List<ContactEntry> result = new ArrayList<>();

        for (String nameToDeserialize :
                contactListJsonNames) {
            result.add((ContactEntry) json.deserialize(nameToDeserialize));
        }

        return result;
    }



    public void appLoop(){

    }

    public void updateContactList(){

    }


    private transient ContactEntry lastCalledContact;


    private transient List<Query> currentQueries;
    private transient int currentQueryInd;

    public void whatsappForward(final ContactEntry contact){
        if(!doingCall){
            doingCall = true;


            CurrentTime currentTime = new CurrentTime();

            int currentHour = currentTime.getTime().getHours();
            int currenMins = currentTime.getTime().getMinutes();



            Hours currentHourEnum = DayOfTheWeek.timeToEnum(currentHour, currenMins);

            if(currentHourEnum != null){
                AvailType currentAvailability = contact.getAvailability().getCurrentDay().get(currentHourEnum);
                // If contact is available just execute withour warnings
                if(currentAvailability.compareTo(AvailType.unavailable) != 0
                        && currentAvailability.compareTo(AvailType.undefined) != 0){
                    if(contact.getCallCount() == 0){
                        askQueriesAndUpdateContactAvailabilityAndDoWhatsappCall(contact);

                    }
                    else{
                        whatsappRedirector.redirectToWhatsappVideoCall(contact, WHATSAPP_VIDEO_CALL_REQUEST);
                        lastCalledContact = contact;
                    }
                }
                // If contact is unavailable or undefined at the moment display warning
                else{
                    displayWarningThatContactIsUnavailableAtTheMoment(contact);
                }
            }
            // If contact is unavailable or undefined at the moment display warning
            else{
                displayWarningThatContactIsUnavailableAtTheMoment(contact);
            }

        }
    }

    public List<ContactEntry> sortContacts(List<ContactEntry> contactsToSort){
        Collections.sort(contactsToSort, new ContactEntryComparator());
        return contactsToSort;
    }

    private void displayWarningThatContactIsUnavailableAtTheMoment(final ContactEntry contact){
        DialogInterface.OnClickListener dialogClickListener = new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                switch (which){
                    case DialogInterface.BUTTON_POSITIVE:
                        if(contact.getCallCount() == 0){
                            askQueriesAndUpdateContactAvailabilityAndDoWhatsappCall(contact);

                        }
                        else{
                            whatsappRedirector.redirectToWhatsappVideoCall(contact, WHATSAPP_VIDEO_CALL_REQUEST);
                            lastCalledContact = contact;
                        }
                        break;

                    case DialogInterface.BUTTON_NEGATIVE:
                        doingCall = false;
                        break;
                }
            }
        };

        DialogInterface.OnDismissListener onDismissDialogListener = new DialogInterface.OnDismissListener(){
            @Override
            public void onDismiss(DialogInterface dialog) {
                doingCall = false;
            }
        };
        AlertDialog.Builder builder = new AlertDialog.Builder(androidContext);
        builder.setMessage("Kontakt jest niedostepny lub nie ma informacji o jego dostepnosci. Na pewno chcesz dzwonic?").setPositiveButton("Tak", dialogClickListener)
                .setNegativeButton("Nie", dialogClickListener).setOnDismissListener(onDismissDialogListener).show();
    }

    private void askQueriesAndUpdateContactAvailabilityAndDoWhatsappCall(final ContactEntry contact) {
        currentQueries = new ArrayList<>();
        currentQueryInd = 0;

        for (QueriesType queryType :
                QueriesType.values()) {
            if(queryType.compareTo(QueriesType.question5) != 0 && queryType.compareTo(QueriesType.question4) != 0){
                currentQueries.add(new Query(queryType, new com.besttime.workhorse.Context(contact, new CurrentTime())));
            }
        }

        DialogInterface.OnClickListener dialogClickListener = new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                switch (which){
                    case DialogInterface.BUTTON_POSITIVE:
                        currentQueries.get(currentQueryInd).setAnswer(true);
                        break;

                    case DialogInterface.BUTTON_NEGATIVE:
                        currentQueries.get(currentQueryInd).setAnswer(false);
                        break;
                }

                if(currentQueryInd < currentQueries.size() - 1){
                    currentQueryInd ++;

                    DialogInterface.OnDismissListener onDismissDialogListener = new DialogInterface.OnDismissListener(){
                        @Override
                        public void onDismiss(DialogInterface dialog) {
                            doingCall = false;
                        }
                    };
                    AlertDialog.Builder builder = new AlertDialog.Builder(androidContext);
                    builder.setMessage(currentQueries.get(currentQueryInd).getQuestion()).setPositiveButton("Tak", this)
                            .setNegativeButton("Nie", this).setOnDismissListener(onDismissDialogListener).show();
                }
                // All queries asked
                else{

                    Week emptySmsWeek = new Week();
                    Week queryWeek = new Week();
                    for (Query query :
                            currentQueries) {

                        List<DayOfTheWeek> generatedResultsFromQueryAnswer = query.generateResult();

                        for (DayOfTheWeek result :
                                generatedResultsFromQueryAnswer) {
                            queryWeek.updateDay(result);
                        }

                    }
                    QuerySmsComputation querySmsComputation = new QuerySmsComputation(emptySmsWeek, queryWeek);

                    contact.getAvailability().setAvailability(querySmsComputation.getWeek());

                    if(contactsWithColorsContainer != null){
                        contactsWithColorsContainer.updateColorsOfCurrentlySelectedContact(contact);
                    }



                    lastCalledContact = contact;
                    json.serialize(lastCalledContact.generateNameForJson(),
                            lastCalledContact);
                    whatsappRedirector.redirectToWhatsappVideoCall(contact, WHATSAPP_VIDEO_CALL_REQUEST);

                }
            }
        };

        DialogInterface.OnDismissListener onDismissDialogListener = new DialogInterface.OnDismissListener(){
            @Override
            public void onDismiss(DialogInterface dialog) {
                doingCall = false;
            }
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(androidContext);
        builder.setMessage(currentQueries.get(currentQueryInd).getQuestion()).setPositiveButton("Tak", dialogClickListener)
                .setNegativeButton("Nie", dialogClickListener).setOnDismissListener(onDismissDialogListener).show();
    }


    public void onWhatsappVideoCallEnd(boolean wasCallAnwsered){

        doingCall = false;

        if(wasCallAnwsered){
            // do sth
        }
        else{
            // do sth else
        }

        if(lastCalledContact.getCallCount() == 0){
            Form newForm = new Form(lastCalledContact.getContactId(),
                    new com.besttime.workhorse.Context(lastCalledContact, new CurrentTime()));
            formManager.sendForm(newForm);
            formManager.addNewFormToSentForms(newForm);
            json.serialize(FormManager.JSON_NAME, formManager);
        }




        if(lastCalledContact.getCallCount() < 3){
            askQuestionAfterCall(lastCalledContact);
        }
        else{
            lastCalledContact.addCallCount();
            json.serialize(lastCalledContact.generateNameForJson(),
                    lastCalledContact);
        }

        json.serialize(App.nameToDeserialize, this);

    }

    private transient Query currentAfterCallQuery;

    private void askQuestionAfterCall(final ContactEntry contact){


        currentAfterCallQuery = new Query(QueriesType.question5, new com.besttime.workhorse.Context(contact, new CurrentTime()));


        DialogInterface.OnClickListener dialogClickListener = new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                        new com.besttime.workhorse.Context(contact, new CurrentTime());
                switch (which){
                    case DialogInterface.BUTTON_POSITIVE:
                        currentAfterCallQuery.setAnswer(true);

                        DayOfTheWeek result = currentAfterCallQuery.generateResult().get(0);

                        Map<Hours, Integer> timesAndTheirAvailability = result.getMap();

                        Hours hourThatUserSelected = null;

                        for (Hours hour :
                                timesAndTheirAvailability.keySet()) {
                            Integer isAvailable = timesAndTheirAvailability.get(hour);
                            if(isAvailable != 0){
                                hourThatUserSelected = hour;
                                break;
                            }
                        }

                        // Can be null if hour is past app hours range (6.00 - 22.00)
                        if(hourThatUserSelected != null){
                            contact.getAvailability().updateOneHour(hourThatUserSelected, 1, (result.getId() + 1) % 7);

                            if(contactsWithColorsContainer != null){
                                contactsWithColorsContainer.updateColorsOfCurrentlySelectedContact(contact);
                            }
                        }


                        break;

                    case DialogInterface.BUTTON_NEGATIVE:
                        currentAfterCallQuery.setAnswer(false);
                        break;
                }

                contact.addCallCount();
                json.serialize(contact.generateNameForJson(), contact);
            }
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(androidContext);
        builder.setMessage(currentAfterCallQuery.getQuestion()).setPositiveButton("Tak", dialogClickListener)
                .setNegativeButton("Nie", dialogClickListener).show();
    }


    public Date getLastLaunch() {
        return lastLaunch;

    }

    public void setLastLaunch(Date lastLaunch) {
        this.lastLaunch = lastLaunch;

    }


    public void checkAllPermissions(){
        if(!checkReadContactPermission()){
            requestReadContactPermission();
        }
        else if(!checkPhoneCallPermission()){
            requestPhoneCallPermission();
        }
        else if(!checkSendSmsPermission()){
            requestSendSmsPermission();
        }
    }


    public boolean checkReadContactPermission(){
        if(androidContext.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED){
            return false;
        }
        return true;
    }

    public boolean checkPhoneCallPermission(){
        if(androidContext.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED){
            return false;
        }
        return true;
    }

    public boolean checkSendSmsPermission(){
        if(androidContext.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED){
            return false;
        }
        return true;
    }


    public void requestReadContactPermission(){
        ((Activity)androidContext).requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, PERMISSIONS_REQUEST_READ_CONTACTS);
    }

    public void requestPhoneCallPermission(){
        ((Activity)androidContext).requestPermissions(new String[]{Manifest.permission.CALL_PHONE}, PERMISSIONS_PHONE_CALL);
    }

    public void requestSendSmsPermission(){
        ((Activity)androidContext).requestPermissions(new String[]{Manifest.permission.SEND_SMS}, PERMISSIONS_REQUEST_SEND_SMS);
    }
}
