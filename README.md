# FHIR Profile Mapper pour Open Integration Engine — essai (POC)

Ce dépôt est un **essai** : un transformateur **graphique** vers des ressources FHIR pour
[Open Integration Engine](https://github.com/OpenIntegrationEngine) (OIE, fork de Mirth Connect).
On choisit un profil FHIR cible, l'éditeur affiche son arbre d'éléments, et on renseigne chaque
élément avec un chemin du message entrant (`msg['PID']['PID.5']['PID.5.1']`), une constante ou une
expression. Plusieurs étapes produisent ensemble un Bundle, par exemple un ORU^R01 de laboratoire
vers un Bundle `transaction`, sans FML ni script écrit à la main.

C'est un **fork** du type de données FHIR pour OIE de Matthy J. de Hoog :
[matthydehoog/fhir-datatype-Eclipse-OIE](https://github.com/matthydehoog/fhir-datatype-Eclipse-OIE).
Ce plugin fournit le type de données FHIR (FHIR JSON et XML en entrée et en sortie, validation HAPI
FHIR contre la spécification R4/R4B/R5 et des profils, traitement par lot) ; sa documentation est
dans le dépôt d'origine. Le fork y ajoute l'étape **FHIR Profile Mapper**, et seulement elle.

> **Statut : preuve de concept.** L'étape fonctionne de bout en bout (voir la démo), mais n'a été
> essayée que sur des exemples. Le format des étapes enregistrées peut encore changer. Pas pour la
> production en l'état.

## Essayer : la démo autonome

[`demo-oru2fhir/`](demo-oru2fhir/) lance OIE 4.7.0 avec l'administration web et ce plugin, compilé
depuis les sources, puis importe un canal qui transforme un ORU^R01 en Bundle FHIR conforme à une IG
d'exemple. Il suffit de Docker :

```bash
cd demo-oru2fhir
docker compose up -d --build
./examples/send.sh            # renvoie le Bundle FHIR
```

Administration web : <https://localhost:8443/oie-webadmin/> (compte `admin`, mot de passe
`demo-oru2fhir-admin`). Le détail est dans le [README de la démo](demo-oru2fhir/README.md).

## Ce que fait l'étape

Une étape de transformer, dans l'**administration web uniquement** (plugin Web Support) :

- **Arbre du profil cible** : construit depuis son snapshot, avec cardinalités, types, Must Support,
  valeurs fixes et patterns. Les types de données et les profils d'extension s'ouvrent à la demande.
- **Mappings** : une expression par élément, qu'on peut glisser depuis le panneau *Message Trees*.
- **Itérations** (↻) : une entrée par répétition (PID-3), ou une ressource par item (une
  Observation par OBX).
- **Conditions** (?) : sur un élément ou sur la ressource.
- **Valeurs fixes du profil** : ajoutées seules, comme les discriminants de slice, l'URL des
  extensions ou `Observation.category`.
- **Conversions de types** : décimaux exacts, dates HL7 v2 vers FHIR.
- **Traductions** (⇄) par `ConceptMap/$translate` :
  - en local, sur les ConceptMaps des paquets de profils ;
  - ou sur un serveur de terminologie.
  - Sans correspondance : laisser vide, garder le code source, une valeur fixe, une fonction, ou une
    erreur.
- **Bundles** : plusieurs étapes alimentent un même Bundle. Chaque entrée a un `fullUrl` en
  `urn:uuid` et sa `request` (PUT conditionnel, `ifNoneExist`). Les références entre ressources
  passent par `fhirRef('patient')`.
- **Aides pour le HL7 v2 « plat » d'OIE** : `hl7Following(obr, 'OBX')`, `hl7Preceding(obr, 'ORC')`,
  `fhirFind`…
- **Contrôles dans l'éditeur** :
  - les éléments obligatoires non couverts ;
  - un test sur un message exemple, avec validation ;
  - le script généré, visible dans l'onglet *Generated Script* et vérifié par *Validate Step*.

L'étape est enregistrée avec ses mappings compilés : le serveur n'a besoin ni de l'éditeur ni du
profil pour l'exécuter. Référence complète : [docs/profile-mapper.md](docs/profile-mapper.md).

## Construire et installer le plugin

JDK 17 et Maven. Les jars du moteur OIE (`com.mirth.connect:*:4.7.0`) ne sont pas sur Maven Central :
il faut les installer une fois depuis une installation d'OIE (voir
[docs/development.md](docs/development.md#building)). Le Dockerfile de la démo le fait tout seul en
les prenant dans l'image OIE.

```bash
mvn clean package          # tests compris ; -DskipTests pour les sauter
node --test webadmin/test/ # tests de la logique de l'éditeur (Node 18+)
```

Le résultat est `target/datatype-fhir-<version>.zip`, à installer dans *Settings > Extensions* ou à
décompresser dans `<OIE_HOME>/extensions/`, puis redémarrer OIE. Il remplace le type de données FHIR
d'origine : n'installer que l'un des deux. L'étape demande l'administration web (plugin Web Support)
et au moins 1 Go de heap (`-Xmx1g` dans `conf/custom.vmoptions`) pour la validation.

## Limites connues

- **Swing** : l'administration Swing ne connaît pas l'étape. Un canal qui l'utilise s'édite dans
  l'administration web.
- **Constructions FHIR non gérées** :
  - extensions sur les valeurs primitives (`_birthDate`) ;
  - ressources `contained` ;
  - sous-slices, et discriminants de type `type`, `profile` ou `exists`.
- **ConceptMap en local** : pas de `dependsOn`/`product`, pas de traduction inverse.
- **Serveur de terminologie** : un appel HTTP synchrone par code nouveau (puis cache), et seulement
  une authentification par en-tête `Authorization`.
- **Test dans l'éditeur** : il exécute l'étape seule, donc les `fhirRef` vers les étapes précédentes y
  renvoient des valeurs de remplacement.
- **Template JSON** : avec un template JSON en sortie (`tmp` objet), les décimaux deviennent des
  nombres.

## Documentation

- [docs/profile-mapper.md](docs/profile-mapper.md) : l'étape FHIR Profile Mapper en détail (anglais).
- [docs/development.md](docs/development.md) : guide de développement du plugin, hérité du dépôt
  d'origine et complété pour l'étape (anglais).
- [demo-oru2fhir/README.md](demo-oru2fhir/README.md) : la démo.

## Licence

[Mozilla Public License 2.0](LICENSE), comme le plugin d'origine. HAPI FHIR est sous licence
Apache 2.0.
