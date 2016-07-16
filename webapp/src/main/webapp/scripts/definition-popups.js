// Requires tipsy.js and tipsy.css to work.

( function ( $ ) {
	var cache = {},
		defaultTipsyOptions = {
			gravity: $.fn.tipsy.autoNS,
			html: true
		},
		API_DELAY = 100;
	
	function processExtract( extract ) {
		return extract
			// usuwanie przypisów
			.replace( /<ref.+?(\/|ref)>/g, '' )
			// zamiana pogrubień na cudzysłowy (np. w czasownikach zwrotnych)
			.replace( /'''(.+?)'''/g, '„$1”' )
			// obsługa szablonu "morfem" z parametrem
			.replace( /\{\{morfem\|[^\|]*\| *(przedrostkowy|przedrostek) *\}\}/, '\'\'morfem przedrostkowy\'\'' )
			.replace( /\{\{morfem\|[^\|]*\| *(przyrostkowy|przyrostek) *\}\}/, '\'\'morfem przyrostkowy\'\'' )
			.replace( /\{\{morfem\|[^\|]*\| *gramatyczny *\}\}/, '\'\'końcówka gramatyczna\'\'' )
			// wykrywanie nagłówka znaczeń; pogrubienie i pochylenie
			.replace( /\n[']{0,2}\{\{(.*?)(\|[^\}]+)?\}\}[']{0,2}\n/g, '\n<i><b>$1</b></i>\n' )
			.replace( /\n''(.+?)''/g, '\n<i><b>$1</b></i>' )
			// kursywa w definicji
			.replace( /''(.+?)''/g, '<i>$1</i>' )
			// usuwa tekst po średniku (np. linki do Wikipedii)
			.replace( /;[^\n]+\n/g, '\n' )
			// obsługa szablonów "zob", "(nie)dokonany od", "odczasownikowy od", "nazwa systematyczna"
			.replace( /\{\{zob\|([^\}]+)\}\}/g, '<i>zob.</i> $1' )
			.replace( /\{\{(nie)?dokonany od\|([^\}]+)\}\}/g, '<i>aspekt $1dokonany od</i> $2' )
			.replace( /\{\{odczasownikowy od\|((nie)\|)?([^\}]+)\}\}/g, '<i>rzecz. odczasownikowy od</i> $2 $3' )
			.replace( /\{\{nazwa systematyczna\|([^\|\}]+?)(\|[^\}]*?)?\}\}/, '<i>$1</i>' )
			// wywołania szablonów z użyciem jednego lub więcej parametrów zastępuje wielokropkiem w nawiasach
			.replace( /\{\{[^\}]+\|.*?\}\}/g, '(…)' )
			// obsługa skrótów
			.replace( /\{\{([^\}]+)\}\}/g, '<i>$1.</i>' )
			// wcięcia na początku definicji
			.replace( /\n: \(/g, '\n(' )
			// nowa linia
			.trim()
			.replace( /\n/g, '<br>' )
			// obsługa linków, zaczerpnięte z Linker::formatLinksInComment() w Linker.php
			.replace(
				/\[\[:?([^\]\|]+)(?:\|((?:\]?[^\]\|])+))*\]\]([^\[]*)/g,
				function ( match, link, text, trail ) {
					return ( text || link ) + trail;
				}
			);
	}
	
	// TODO: move parse operations to Java code via Servlet (or call RestAPI)
	function parseArticle( text, lang ) {
		var langheader, langend, langsection, defheader, declheader, defs;
		
		langheader = text.search( new RegExp(
			'\\{\\{(język )?' +
			lang.replace( '(', '\\(' ).replace( ')', '\\)' )
		) );
		
		if ( lang === 'polski' && langheader === -1 ) {
			langheader = text.indexOf( '{{termin obcy w języku polskim' );
		}
		
		if ( langheader === -1 ) {
			return null;
		}

		langend = text.indexOf( '\n== ', langheader );
		langsection = text.slice( langheader, ( langend === -1 )
			? text.length
			: langend
		);
		defheader = langsection.indexOf( '{' + '{znaczenia}}' );
		declheader = langsection.indexOf( '{' + '{odmiana}}', defheader );
		
		if ( defheader === -1 || declheader === -1 ) {
			return null;
		}
		
		defs = langsection.slice( defheader + 13, declheader );
		
		return processExtract( defs );
	};
	
	function makeRequest( title ) {
		return $.ajax( {
			url: 'https://pl.wiktionary.org/w/api.php',
			data: {
				action: 'query',
				prop: 'revisions',
				rvprop: 'content',
				titles: title,
				format: 'json',
				formatversion: 2,
				origin: '*'
			},
			contentType: 'application/json',
			dataType: 'json'
		} );
	}
	
	function bindTipsyActions( $el, cacheKey ) {
		$el.tipsy( $.extend( {}, defaultTipsyOptions, {
			title: function () {
				return cache[ cacheKey ];
			}
		} ) );
	}
	
	$.fn.definitionPopups = function () {
		return this.filter( '[data-target][data-section]' ).not( '.new, false-blue' ).each( function () {
			var $el = $( this ),
				target = $el.attr( 'data-target' ),
				section = $el.attr( 'data-section' ),
				cacheKey = target + '#' + section;
			
			$el.attr( 'original-title', $el.attr( 'title' ) ).removeAttr( 'title' );
			
			$el.on( 'mouseenter.definition', function ( evt ) {
				var timerID;
				
				if ( cache.hasOwnProperty( cacheKey ) && cache[ cacheKey ] !== null ) {
					$el.off( 'mouseenter.definition mouseleave.definition' );
					bindTipsyActions( $el, cacheKey );
					$el.trigger( 'mouseenter' );
					return;
				}
				
				timerID = setTimeout( function () {
					var request = makeRequest( target );
					
					$el.data( 'request', request ).removeData( 'timer-id' );
					
					request.done( function ( json ) {
						var pageText, parsed;
					
						$el.off( 'mouseenter.definition mouseleave.definition' );
						
						try {
							pageText = json.query.pages[0].revisions[0].content;
							parsed = parseArticle( pageText, section );
							cache[ cacheKey ] = parsed.toString(); // force an exception if null
						} catch ( TypeError ) {
							return;
						}
						
						bindTipsyActions( $el, cacheKey );
						
						if ( $el.is( ':hover' ) ) {
							$el.trigger( 'mouseenter' );
						}
					} );
				}, API_DELAY );
				
				$el.data( 'timer-id', timerID );
			} );
			
			$el.on( 'mouseleave.definition', function ( evt ) {
				var timerID = $el.data( 'timer-id' ),
					request = $el.data( 'request' );
				
				if ( timerID !== undefined ) {
					clearTimeout( timerID );
				}
				
				if ( request !== undefined ) {
					request.abort();
				}
				
				$el.removeData( [ 'timer-id', 'request' ] );
			} );
		} );
	};
}( jQuery ) );
