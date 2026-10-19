"""The offices the Android app watches, with the same calendars and default services
(android/.../source/Offices.kt). A default service is the shortest one of its calendar:
services of one calendar share the counters, so its free start times cover every gap a
longer service would fit into.
"""

from dataclasses import dataclass

LRA = "Landratsamt Alb-Donau-Kreis"
LRA_FS = "https://lra-alb-donau-kreis.saas.smartcjm.com/m/abh-fs/extern/calendar/"
LRA_ABH = "https://lra-alb-donau-kreis.saas.smartcjm.com/m/abh/extern/calendar/"
EHINGEN = "Stadt Ehingen (Donau)"
EHINGEN_URL = "https://ehingen.flexappoint.de"


@dataclass(frozen=True)
class SmartCjmOffice:
    id: str
    name: str
    authority: str
    url: str
    uid: str
    # The wizard only accepts postcodes the location is responsible for.
    zip_code: str
    service: str
    # Set for calendars shared by several locations.
    location: str | None = None
    system: str = "smartcjm"

    @property
    def booking_url(self):
        return f"{self.url}?uid={self.uid}"


@dataclass(frozen=True)
class FlexappointOffice:
    id: str
    name: str
    authority: str
    url: str
    department: int
    service: int
    horizon_days: int = 60
    system: str = "flexappoint"

    @property
    def booking_url(self):
        return self.url


OFFICES = [
    # Außerbetriebsetzung / Abmeldung, 10 minutes; every other service is 15.
    SmartCjmOffice("lra-kfz-ehingen", "Kfz-Zulassung Ehingen", LRA, LRA_FS,
                   "fd698344-7f8a-4ab8-a9c4-1020378e4655", "89584", "48f60d85-2adc-4fe9-8f2f-c122028906c1"),
    # Joint office of Ulm and the Landkreis.
    SmartCjmOffice("lra-kfz-ulm", "Kfz-Zulassung Ulm", LRA, LRA_FS,
                   "61003675-f85e-4f2b-ab4a-4a29306d5c04", "89584", "19efd142-c5e8-437f-97b1-1e3a8988f9b6"),
    SmartCjmOffice("lra-kfz-langenau", "Kfz-Zulassung Langenau", LRA, LRA_FS,
                   "72379614-9118-4d1b-b55a-7c5f83de45ed", "89129", "c95f1e45-6513-42ab-be2b-2833e29196ff"),
    # Abholung Führerschein, 10 minutes.
    SmartCjmOffice("lra-fuehrerschein-ehingen", "Führerscheinstelle Ehingen", LRA, LRA_FS,
                   "38bb0462-011f-4331-ada4-c904f6a390d1", "89584", "f7ed0928-a143-4cca-912f-905850b90087"),
    SmartCjmOffice("lra-fuehrerschein-ulm", "Führerscheinstelle Ulm", LRA, LRA_FS,
                   "712d84df-ba20-4b64-b718-d00789e6555b", "89584", "f7ed0928-a143-4cca-912f-905850b90087"),
    # Abholung eAT, Einzelperson, 15 minutes. The town of Ehingen runs its own office
    # for its residents, hence a Landkreis postcode outside it.
    SmartCjmOffice("lra-auslaenderbehoerde-ulm", "Ausländerbehörde Ulm (Landkreis)", LRA, LRA_ABH,
                   "d45a683e-595a-4afe-b88e-510dfa0ec13d", "89597", "29ec56c3-f289-4d1a-9f16-014262d51eeb",
                   location="947037c3-f458-4d4d-9a1e-0b3a8bf53b39"),
    SmartCjmOffice("lra-auslaenderbehoerde-ehingen", "Ausländerbehörde Ehingen (Landkreis)", LRA, LRA_ABH,
                   "d45a683e-595a-4afe-b88e-510dfa0ec13d", "89597", "29ec56c3-f289-4d1a-9f16-014262d51eeb",
                   location="d1a57f7e-5127-4fac-bc8e-a89bdad9ed9c"),
    # Meldebescheinigung, 10 minutes on the 15 minute grid.
    FlexappointOffice("ehingen-buergerbuero", "Bürgerbüro Ehingen", EHINGEN, EHINGEN_URL, 1, 8),
    # Abholung Fiktionsbescheinigung; every service here takes 20 minutes.
    FlexappointOffice("ehingen-auslaenderbehoerde", "Ausländerbehörde Ehingen", EHINGEN, EHINGEN_URL, 2, 62),
    # Rentenantrag: Altersrente, 105 minutes; the others take 120.
    FlexappointOffice("ehingen-rentenstelle", "Rentenstelle Ehingen", EHINGEN, EHINGEN_URL, 4, 38),
]

OFFICES_BY_ID = {office.id: office for office in OFFICES}
